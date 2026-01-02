package org.marshive.util;

import lombok.extern.slf4j.Slf4j;
import org.marshive.dao.ClientDAO;
import org.marshive.dao.RoomDAO;
import org.marshive.domain.Client;
import org.marshive.domain.Room;

import java.util.Collection;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;

/**
 * 提供一个交互式指令窗口
 */
@Slf4j
public class InstructionHelper implements Runnable {
    private final static InstructionHelper instance = new InstructionHelper();
    private volatile static boolean running = false;
    
    public static void run(ExecutorService service) {
        if (!running) {
            synchronized (InstructionHelper.class) {
                if (!running) {
                    running = true;
                    service.execute(instance);
                }
            }
        }
    }
    
    private final ClientDAO clientDAO = ClientDAO.getInstance();
    private final RoomDAO roomDAO = RoomDAO.getInstance();
    
    @Override
    public void run() {
        log.debug("指令窗口已启动，输入 'help' 获取指令列表");
        Scanner scanner = new Scanner(System.in);
        while (true) {
            String line = scanner.nextLine();
            String[] parts = line.trim().split("\\s+");
            String command = parts[0].toLowerCase();
            switch (command) {
                case "":
                    break;
                case "help":
                case "h":
                    System.out.println("可用指令：");
                    System.out.println("  clients, c   - 列出所有连接的客户端");
                    System.out.println("  rooms, r     - 列出所有房间");
                    break;
                case "clients":
                case "c":
                    final Collection<Client> clients = clientDAO.allClients();
                    if (clients.isEmpty()) {
                        System.out.println("当前没有连接的客户端");
                        break;
                    }
                    System.out.println("连接的客户端列表：");
                    clients.forEach(c -> {
                        System.out.println("- " + c.getChannel().remoteAddress());
                    });
                    break;
                case "rooms":
                case "r":
                    final Collection<Room> rooms = roomDAO.allRooms();
                    if (rooms.isEmpty()) {
                        System.out.println("当前没有房间");
                        break;
                    }
                    System.out.println("房间列表：");
                    rooms.forEach(r -> {
                        final Client host = r.getHost();
                        final Client guest = r.getGuest();
                        System.out.println("- 房间ID: " + r.getId() + 
                                           " (host=" + (host != null ? host.getChannel().remoteAddress() : "null") + 
                                           ", guest=" + (guest != null ? guest.getChannel().remoteAddress() : "null") + ")");
                    });
                    break;
                default:
                    System.out.println("未知指令: " + command + "。输入 help 获取指令列表。");
                    break;
            }
        }
    }
}
