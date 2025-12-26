package org.marshive.domain.data;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.marshive.constant.RequestType;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequestBody<T> {
    private RequestType type;
    private T payload;
}
