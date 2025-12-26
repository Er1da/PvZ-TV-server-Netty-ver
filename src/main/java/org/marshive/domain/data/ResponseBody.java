package org.marshive.domain.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.marshive.constant.ResponseType;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResponseBody<T> {
    private ResponseType type;
    private T data;
    
    public static <T> ResponseBody<T> of(ResponseType type, T data) {
        return new ResponseBody<>(type, data);
    }
    
    public static <T> ResponseBody<T> of(ResponseType type) {
        return new ResponseBody<>(type, null);
    }
    
    /* 默认响应 */
    public static final ResponseBody<byte[]> BadRequest = new ResponseBody<>(ResponseType.ERROR, new byte[]{0x01});
    public static final ResponseBody<byte[]> NotFound   = new ResponseBody<>(ResponseType.ERROR, new byte[]{0x02});
    public static final ResponseBody<byte[]> RoomFull   = new ResponseBody<>(ResponseType.ERROR, new byte[]{0x03});
    public static final ResponseBody<byte[]> NotHost    = new ResponseBody<>(ResponseType.ERROR, new byte[]{0x04});
    public static final ResponseBody<byte[]> NotReady   = new ResponseBody<>(ResponseType.ERROR, new byte[]{0x05});
}
