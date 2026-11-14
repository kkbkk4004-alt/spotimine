package com.example.spotifybar;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/** Tiny JNA binding for the few Windows functions we need (JNA ships with Minecraft). */
public interface User32 extends StdCallLibrary {
	User32 INSTANCE = Native.load("user32", User32.class);

	interface EnumProc extends StdCallCallback {
		boolean callback(Pointer hWnd, Pointer data);
	}

	boolean EnumWindows(EnumProc proc, Pointer data);

	int GetWindowTextW(Pointer hWnd, char[] buffer, int max);

	int GetClassNameW(Pointer hWnd, char[] buffer, int max);

	int GetWindowThreadProcessId(Pointer hWnd, IntByReference pid);

	void keybd_event(byte vk, byte scan, int flags, Pointer extra);
}
