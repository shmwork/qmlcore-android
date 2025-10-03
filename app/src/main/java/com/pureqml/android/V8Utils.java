package com.pureqml.android;

import com.eclipsesource.v8.V8Array;
import com.eclipsesource.v8.V8Object;
import com.eclipsesource.v8.V8Function;

/**
 * Utilities to quietly release V8 native handles without throwing.
 */
public final class V8Utils {
    private V8Utils() {}

    public static void closeQuietly(V8Array a) {
        if (a == null) return;
        try { a.release(); } catch (Throwable ignored) {}
    }

    public static void closeQuietly(V8Object o) {
        if (o == null) return;
        try { o.release(); } catch (Throwable ignored) {}
    }

    public static void closeQuietly(V8Function f) {
        if (f == null) return;
        try { f.release(); } catch (Throwable ignored) {}
    }
}
