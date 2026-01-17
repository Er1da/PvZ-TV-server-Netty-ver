package org.marshive.parse;

import lombok.extern.slf4j.Slf4j;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 负责将解析后的帧数据存储到文件的类。
 * 
 * 存储格式：
 * - 每个事件占一行，以换行符(\n)分隔
 * - 事件格式: +时间戳 @事件名称 $$ 数据1 $$ 数据2 $$ ...
 * - 数据之间使用 $$ 和空格分隔
 */
@Slf4j
public class FrameStorage {
    private static final String DEFAULT_DIR = "frames";
    private static final String DATE_FORMAT = "yyyyMMdd_HHmmss";
    
    private final String sessionId;
    private final File storageFile;
    private BufferedWriter writer;
    private volatile boolean closed = false;
    
    /**
     * 创建一个新的帧存储实例
     * @param sessionId 会话标识，用于区分不同的对战
     */
    public FrameStorage(String sessionId) {
        this.sessionId = sessionId;
        this.storageFile = createStorageFile();
        initWriter();
    }
    
    private File createStorageFile() {
        File dir = new File(DEFAULT_DIR);
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                log.info("创建帧数据存储目录: {}", dir.getAbsolutePath());
            }
        }
        
        SimpleDateFormat sdf = new SimpleDateFormat(DATE_FORMAT);
        String timestamp = sdf.format(new Date());
        String filename = String.format("frames_%s_%s.txt", sessionId, timestamp);
        
        return new File(dir, filename);
    }
    
    private void initWriter() {
        try {
            writer = new BufferedWriter(new FileWriter(storageFile, true));
            log.info("帧数据将存储到文件: {}", storageFile.getAbsolutePath());
        } catch (IOException e) {
            log.error("无法创建帧存储文件: {}", e.getMessage());
        }
    }
    
    /**
     * 存储一个解析后的帧
     * @param frame 要存储的帧
     */
    public synchronized void store(ParsedFrame frame) {
        if (closed || writer == null) {
            return;
        }
        
        try {
            writer.write(frame.toStorageFormat());
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("写入帧数据失败: {}", e.getMessage());
        }
    }
    
    /**
     * 直接存储一行格式化的数据
     * @param line 格式化的数据行
     */
    public synchronized void storeLine(String line) {
        if (closed || writer == null) {
            return;
        }
        
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("写入数据行失败: {}", e.getMessage());
        }
    }
    
    /**
     * 关闭存储并释放资源
     */
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        
        if (writer != null) {
            try {
                writer.close();
                log.info("帧存储文件已关闭: {}", storageFile.getAbsolutePath());
            } catch (IOException e) {
                log.error("关闭帧存储文件失败: {}", e.getMessage());
            }
        }
    }
    
    /**
     * 获取存储文件路径
     * @return 文件路径
     */
    public String getFilePath() {
        return storageFile.getAbsolutePath();
    }
    
    /**
     * 获取会话ID
     * @return 会话ID
     */
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * 检查存储是否已关闭
     * @return 是否已关闭
     */
    public boolean isClosed() {
        return closed;
    }
}
