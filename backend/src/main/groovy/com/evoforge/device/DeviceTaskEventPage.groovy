package com.evoforge.device

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

class DeviceTaskEventPage {
    List<DeviceTaskEvent> items = []
    boolean hasMoreBefore = false
    boolean hasMoreAfter = false
    String beforeCursor = ''
    String afterCursor = ''
    int limit = 80

    Map<String, Object> toMap() {
        return [
            items        : items ?: [],
            hasMoreBefore: hasMoreBefore,
            hasMoreAfter : hasMoreAfter,
            beforeCursor : beforeCursor ?: '',
            afterCursor  : afterCursor ?: '',
            limit        : limit
        ] as Map<String, Object>
    }

    static DeviceTaskEventPage fromItems(List<DeviceTaskEvent> events,
                                         int limit,
                                         boolean hasMoreBefore = false,
                                         boolean hasMoreAfter = false) {
        List<DeviceTaskEvent> ordered = (events ?: []).sort { a, b ->
            compareEventPosition(a.createdAt, a.eventId, b.createdAt, b.eventId)
        }
        return new DeviceTaskEventPage(
            items: ordered,
            hasMoreBefore: hasMoreBefore,
            hasMoreAfter: hasMoreAfter,
            beforeCursor: ordered ? cursorFor(ordered.first()) : '',
            afterCursor: ordered ? cursorFor(ordered.last()) : '',
            limit: limit
        )
    }

    static String cursorFor(DeviceTaskEvent event) {
        if (!event?.createdAt || !event.eventId) {
            return ''
        }
        String raw = "${event.createdAt}|${event.eventId}".toString()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8))
    }

    static Cursor parseCursor(String cursor) {
        String value = cursor == null ? '' : cursor.trim()
        if (!value) {
            return null
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
            int split = raw.indexOf('|')
            if (split <= 0 || split >= raw.length() - 1) {
                return null
            }
            return new Cursor(
                createdAt: Instant.parse(raw.substring(0, split)),
                eventId: raw.substring(split + 1)
            )
        } catch (Exception ignored) {
            return null
        }
    }

    static int compareEventPosition(Instant aTime, String aId, Instant bTime, String bId) {
        int byTime = (aTime ?: Instant.EPOCH) <=> (bTime ?: Instant.EPOCH)
        if (byTime != 0) {
            return byTime
        }
        return (aId ?: '') <=> (bId ?: '')
    }

    static class Cursor {
        Instant createdAt
        String eventId
    }
}
