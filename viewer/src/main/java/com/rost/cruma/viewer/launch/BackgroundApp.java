package com.rost.cruma.viewer.launch;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import org.apache.logging.log4j.LogManager;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.Platform;

public final class BackgroundApp {
    private static final long ACCESSORY = 1;
    private static final long USER_INITIATED = 0x00FFFFFFL;

    private BackgroundApp() {}

    public static void start(String reason) {
        if (Platform.get() != Platform.MACOSX) {
            return;
        }
        GLFW.glfwInitHint(GLFW.GLFW_COCOA_MENUBAR, GLFW.GLFW_FALSE);
        try (var arena = Arena.ofConfined()) {
            var lookup = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
            var linker = Linker.nativeLinker();
            var getClass =
                    linker.downcallHandle(lookup.findOrThrow("objc_getClass"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            var selector = linker.downcallHandle(
                    lookup.findOrThrow("sel_registerName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            var message = lookup.findOrThrow("objc_msgSend");
            var get = linker.downcallHandle(message, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            var setNumber = linker.downcallHandle(message, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_LONG));
            var text = linker.downcallHandle(message, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            var activity = linker.downcallHandle(
                    message, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));

            var application = (MemorySegment) get.invokeExact(
                    (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSApplication")),
                    (MemorySegment) selector.invokeExact(arena.allocateFrom("sharedApplication")));
            setNumber.invokeExact(
                    application,
                    (MemorySegment) selector.invokeExact(arena.allocateFrom("setActivationPolicy:")),
                    ACCESSORY);

            var words = (MemorySegment) text.invokeExact(
                    (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSString")),
                    (MemorySegment) selector.invokeExact(arena.allocateFrom("stringWithUTF8String:")),
                    (MemorySegment) arena.allocateFrom(reason));
            var process = (MemorySegment) get.invokeExact(
                    (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSProcessInfo")),
                    (MemorySegment) selector.invokeExact(arena.allocateFrom("processInfo")));
            var running = (MemorySegment) activity.invokeExact(
                    process,
                    (MemorySegment) selector.invokeExact(arena.allocateFrom("beginActivityWithOptions:reason:")),
                    USER_INITIATED,
                    words);
            LogManager.getLogger(BackgroundApp.class)
                    .debug("Working in the background as activity {}", running.address());
        } catch (Throwable failure) {
            LogManager.getLogger(BackgroundApp.class)
                    .warn("Could not work quietly in the background: {}", failure.toString());
        }
    }
}
