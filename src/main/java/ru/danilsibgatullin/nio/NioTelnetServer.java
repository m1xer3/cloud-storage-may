package ru.danilsibgatullin.nio;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;


public class NioTelnetServer {
	public static final String LS_COMMAND = "\tls    view all files and directories\n";
	public static final String MKDIR_COMMAND = "\tmkdir    create directory\n";
	public static final String CHANGE_NICKNAME = "\tnick    change nickname\n";
	private static final Path SERVER_PATH = Path.of("server/");
	private Path currentPath = SERVER_PATH;
	private String userName ="user";
	private String prefixCommand ="";
	private SocketAddress client;

	private final ByteBuffer buffer = ByteBuffer.allocate(512);

	public NioTelnetServer() throws IOException {
		ServerSocketChannel server = ServerSocketChannel.open();
		server.bind(new InetSocketAddress(5678));
		server.configureBlocking(false);
		// OP_ACCEPT, OP_READ, OP_WRITE
		Selector selector = Selector.open();

		server.register(selector, SelectionKey.OP_ACCEPT);
		System.out.println("Server started");

		while (server.isOpen()) {
			sendMessage(prefixCommand+"->"+currentPath.toString()+": ",selector,client); //префикс перед вводом
			selector.select();

			var selectionKeys = selector.selectedKeys();
			var iterator = selectionKeys.iterator();

			while (iterator.hasNext()) {
				var key = iterator.next();
				if (key.isAcceptable()) {
					handleAccept(key, selector);
				} else if (key.isReadable()) {
					handleRead(key, selector);
				}
				iterator.remove();
			}
		}
	}

	private void handleRead(SelectionKey key, Selector selector) throws IOException {
		SocketChannel channel = ((SocketChannel) key.channel());
		client = channel.getRemoteAddress();
		int readBytes = channel.read(buffer);
		if (readBytes < 0) {
			channel.close();
			return;
		} else if (readBytes == 0) {
			return;
		}

		buffer.flip();

		StringBuilder sb = new StringBuilder();
		while (buffer.hasRemaining()) {
			sb.append((char) buffer.get());
		}

		buffer.clear();

		// TODO
		// touch [filename] - создание файла
		// mkdir [dirname] - создание директории
		// cd [path] - перемещение по каталогу (.. | ~ )
		// rm [filename | dirname] - удаление файла или папки
		// copy [src] [target] - копирование файла или папки
		// cat [filename] - просмотр содержимого
		// вывод nickname в начале строки

		// NIO
		// NIO telnet server

		if (key.isValid()) {
			String command = sb
					.toString()
					.replace("\n", "")
					.replace("\r", "");

			if ("--help".equals(command)) {
				sendMessage(LS_COMMAND, selector, client);
				sendMessage(MKDIR_COMMAND, selector, client);
				sendMessage(CHANGE_NICKNAME, selector, client);
			} else if ("ls".equals(command)) {
				sendMessage(getFileList().concat("\n"), selector, client);
			} else if (command.startsWith("touch")){
				createFile(command.substring("touch ".length()));
			} else if (command.startsWith("cd")){
				changeDir(command.substring("cd ".length()));
			} else if (command.startsWith("mkdir")){
				mkDir(command.substring("mkdir ".length()));
			} else if (command.startsWith("rm")){
				delDir(command.substring("rm ".length()));
			} else if (command.startsWith("copy")){
				String[] arrCommand = command.split(" ");
				if(arrCommand.length==3){
					copyFile(arrCommand[1],arrCommand[2]);
				}else {
					throw new IOException();
				}
			} else if (command.startsWith("cat")){
				viewFile(command.substring("cat ".length()),selector, client);
			} else if ("exit".equals(command)) {
				System.out.println("Client logged out. IP: " + channel.getRemoteAddress());
				channel.close();
				return;
			}
		}
	}

	private void viewFile(String filename,Selector selector, SocketAddress client) throws IOException {
		Path readPath =Path.of(currentPath.toString() +"/" +filename);
		 Files.readAllLines(readPath).stream().peek((e) -> {
			try {
				sendMessage(e+"\n",selector, client);
			} catch (IOException ex) {
				ex.printStackTrace();
			}
		}).collect(Collectors.toList());
	}

	private void copyFile(String filename,String pathTo) throws IOException {
		Path fromPath =Path.of(currentPath.toString() +"/" +filename);
		Path toPath = Path.of(pathTo+"/"+filename);
		if(Files.exists(fromPath)){
			if (!Files.exists(toPath)){
				Files.copy(fromPath,toPath, StandardCopyOption.REPLACE_EXISTING);
			}
		}
	}

	private void delDir(String dirName) throws IOException {
		dirName=currentPath.toString()+ "/" + dirName;
		Path delPath = Path.of(dirName);
		Files.walk(delPath)
				.sorted(Comparator.reverseOrder()) //сортируем в обратной последовательности
				.map(Path::toFile)
				.forEach(File::delete); //удаляем с обратной стороны что бы не было DirectoryNotEmptyException
	}

	private String getFileList() {
		return String.join(" ", new File(currentPath.toString()).list());
	}

	private void createFile(String filename) throws IOException {
		Path newPath = Path.of(currentPath.toString(),filename);
		if (!Files.exists(newPath)) {
			Files.createFile(newPath);
		}
	}

	private void changeDir(String newDir){
		if ("~".equals(newDir)){
			currentPath=SERVER_PATH;
			return;
		}else if("..".equals(newDir)){
			int lastDirindex = currentPath.toString().lastIndexOf("/");// ищем последний разделитель директорий
			newDir=currentPath.toString().substring(0,lastDirindex);
			currentPath=Path.of(newDir);
			return;
		}else {
			newDir = currentPath.toString() + "/" + newDir;
			currentPath = Path.of(newDir);
		}
	}

	private void mkDir(String newDir) throws IOException {
		newDir = currentPath.toString() + "/" + newDir;
		Files.createDirectory(Path.of(newDir));
	}



	private void sendMessage(String message, Selector selector, SocketAddress client) throws IOException {
		for (SelectionKey key : selector.keys()) {
			if (key.isValid() && key.channel() instanceof SocketChannel) {
				if (((SocketChannel)key.channel()).getRemoteAddress().equals(client)) {
					((SocketChannel)key.channel())
							.write(ByteBuffer.wrap(message.getBytes(StandardCharsets.UTF_8)));
				}
			}
		}
	}

	private void handleAccept(SelectionKey key, Selector selector) throws IOException {
		SocketChannel channel = ((ServerSocketChannel) key.channel()).accept();
		channel.configureBlocking(false);
		System.out.println("Client accepted. IP: " + channel.getRemoteAddress());

		prefixCommand="("+userName+"@"+channel.getRemoteAddress()+")";
		channel.register(selector, SelectionKey.OP_READ, "some attach");
		channel.write(ByteBuffer.wrap("Hello user!\n".getBytes(StandardCharsets.UTF_8)));
		channel.write(ByteBuffer.wrap("Enter --help for support info\n".getBytes(StandardCharsets.UTF_8)));
	}

	public static void main(String[] args) throws IOException {
		new NioTelnetServer();
	}
}
