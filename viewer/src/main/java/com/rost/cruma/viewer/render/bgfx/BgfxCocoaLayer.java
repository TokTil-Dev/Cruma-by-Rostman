package com.rost.cruma.viewer.render.bgfx;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;

final class BgfxCocoaLayer {
    private BgfxCocoaLayer() {}

    static long attach(long window) {
        if (window == 0) {
            throw new IllegalArgumentException("null Cocoa window");
        }
        try (var arena = Arena.ofConfined()) {
            var lookup = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
            var linker = Linker.nativeLinker();
            var getClass =
                    linker.downcallHandle(lookup.findOrThrow("objc_getClass"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            var selector = linker.downcallHandle(
                    lookup.findOrThrow("sel_registerName"), FunctionDescriptor.of(ADDRESS, ADDRESS));
            var message = lookup.findOrThrow("objc_msgSend");
            var get = linker.downcallHandle(message, FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
            var set = linker.downcallHandle(message, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
            var setBoolean = linker.downcallHandle(message, FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_BYTE));
            var booleanValue = linker.downcallHandle(message, FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS));
            var isKind = linker.downcallHandle(message, FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS, ADDRESS));
            var threadClass = (MemorySegment) getClass.invokeExact(arena.allocateFrom("NSThread"));
            var mainSelector = (MemorySegment) selector.invokeExact(arena.allocateFrom("isMainThread"));
            if ((byte) booleanValue.invokeExact(threadClass, mainSelector) == 0) {
                throw new IllegalStateException("Metal layer installation requires Cocoa's main thread");
            }
            var viewSelector = (MemorySegment) selector.invokeExact(arena.allocateFrom("contentView"));
            var view = (MemorySegment) get.invokeExact(MemorySegment.ofAddress(window), viewSelector);
            var layerClass = (MemorySegment) getClass.invokeExact(arena.allocateFrom("CAMetalLayer"));
            if (view.address() == 0 || layerClass.address() == 0) {
                throw new IllegalStateException("Cocoa window or Metal layer class is unavailable");
            }
            var layerSelector = (MemorySegment) selector.invokeExact(arena.allocateFrom("layer"));
            var layer = (MemorySegment) get.invokeExact(view, layerSelector);
            var kindSelector = (MemorySegment) selector.invokeExact(arena.allocateFrom("isKindOfClass:"));
            if (layer.address() != 0 && (byte) isKind.invokeExact(layer, kindSelector, layerClass) != 0) {
                return layer.address();
            }
            layer = (MemorySegment) get.invokeExact(layerClass, layerSelector);
            if (layer.address() == 0) {
                throw new IllegalStateException("CAMetalLayer allocation failed");
            }
            var wantsSelector = (MemorySegment) selector.invokeExact(arena.allocateFrom("setWantsLayer:"));
            setBoolean.invokeExact(view, wantsSelector, (byte) 1);
            var setSelector = (MemorySegment) selector.invokeExact(arena.allocateFrom("setLayer:"));
            set.invokeExact(view, setSelector, layer);
            return layer.address();
        } catch (Throwable failure) {
            if (failure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Cocoa Metal layer setup failed", failure);
        }
    }
}
