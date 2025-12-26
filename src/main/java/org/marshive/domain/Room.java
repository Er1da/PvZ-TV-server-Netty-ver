package org.marshive.domain;

import lombok.Data;
import lombok.RequiredArgsConstructor;

@Data
@RequiredArgsConstructor
public class Room {
    private final int id;
    private final String name;
    private final Client host;
    private volatile Client guest;
    private volatile boolean gaming = false;
    
    public boolean isFull() {
        return host != null && guest != null;
    }
}
