package com.watchwise.watchwise_api.userlist.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

final class UserListOrderCursorCodec {

    private static final String VERSION = "v1";

    private UserListOrderCursorCodec() { }

    static Position decode(String cursor, String scopeType, UUID scopeId) {
        if (cursor == null) return null;
        if (cursor.length() > 200) throw new BadRequestException("Order cursor is too long");
        if (cursor.isBlank()) return null;
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] values = decoded.split("\\|", -1);
            if (values.length != 6 || !VERSION.equals(values[0]) || !scopeType.equals(values[1])
                    || !scopeId.toString().equals(values[2])) {
                throw new BadRequestException("Order cursor does not match this resource");
            }
            long revision = Long.parseLong(values[3]);
            int position = Integer.parseInt(values[4]);
            UUID itemId = UUID.fromString(values[5]);
            if (revision < 0 || position < 0) throw new IllegalArgumentException();
            return new Position(revision, position, itemId);
        } catch (BadRequestException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid order cursor");
        }
    }

    static String encode(String scopeType, UUID scopeId, long revision, int position, UUID itemId) {
        String value = String.join("|", VERSION, scopeType, scopeId.toString(), Long.toString(revision),
                Integer.toString(position), itemId.toString());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    static long parseRevision(String revision) {
        try {
            long parsed = Long.parseLong(revision);
            if (parsed < 0) throw new IllegalArgumentException();
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new BadRequestException("Invalid order revision");
        }
    }

    record Position(long revision, int position, UUID itemId) { }
}
