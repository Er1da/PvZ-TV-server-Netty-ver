package org.marshive.parse;

import java.util.List;

/**
 * 帧数据解析监听器接口。
 * 用于在 {@link FrameBuffer} 完成帧解析时进行异步回调通知。
 * 
 * <p>实现此接口可以在帧解析完成后执行自定义逻辑，如存储、分析、转发等。</p>
 */
public interface FrameParsingListener {
    
    /**
     * 当帧解析完成时调用。
     * 此方法在解析线程中异步执行。
     * 
     * @param frame 解析完成的单个帧数据
     */
    void onFrameParsed(ParsedFrame frame);
    
    /**
     * 当批量帧解析完成时调用。
     * 默认实现会逐个调用 {@link #onFrameParsed(ParsedFrame)}。
     * 
     * @param frames 解析完成的帧列表
     */
    default void onFramesParsed(List<ParsedFrame> frames) {
        if (frames != null) {
            for (ParsedFrame frame : frames) {
                onFrameParsed(frame);
            }
        }
    }
    
    /**
     * 当解析发生异常时调用。
     * 
     * @param cause 异常原因
     */
    default void onParsingError(Throwable cause) {
        // 默认空实现，子类可选择覆盖
    }
    
    /**
     * 当解析器释放资源时调用。
     * 可用于释放监听器持有的资源。
     */
    default void onParsingClosed() {
        // 默认空实现，子类可选择覆盖
    }
}
