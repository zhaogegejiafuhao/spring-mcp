package com.acme.nested;

/**
 * Handlers are only referenced, never called; the analyzer reads the router declarations.
 */
public class ConsoleHandler {

    public Object listPosts(Object request) {
        return null;
    }

    public Object createPost(Object request) {
        return null;
    }

    public Object listTags(Object request) {
        return null;
    }

    public Object health(Object request) {
        return null;
    }
}
