package com.chronos.tracker.activity;

import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;

import java.time.Duration;

/**
 * Tempo desde o último teclado ou mouse no Windows, pela API {@code GetLastInputInfo}. Vale para o PC
 * inteiro, não só para a janela do Chronos; com a tela bloqueada não há input, então o tempo ocioso cresce.
 */
public final class WindowsIdleMonitor implements ActivityMonitor {

    private final WinUser.LASTINPUTINFO info = new WinUser.LASTINPUTINFO();

    /** Lança se a API não estiver disponível (fora do Windows ou sem a biblioteca nativa). */
    public WindowsIdleMonitor() {
        read();
    }

    @Override
    public synchronized Duration getIdleTime() {
        try {
            return read();
        } catch (RuntimeException | LinkageError e) {
            // Uma falha pontual não pode pausar o tempo de ninguém: trata como ativo.
            return Duration.ZERO;
        }
    }

    private Duration read() {
        if (!User32.INSTANCE.GetLastInputInfo(info)) {
            throw new IllegalStateException("GetLastInputInfo falhou");
        }
        return idleBetween(Kernel32.INSTANCE.GetTickCount(), info.dwTime);
    }

    /**
     * Os dois valores são milissegundos desde que o Windows ligou, em 32 bits sem sinal: voltam a zero a cada
     * ~49 dias, então a diferença também é calculada sem sinal.
     */
    static Duration idleBetween(int nowTicks, int lastInputTicks) {
        return Duration.ofMillis(Integer.toUnsignedLong(nowTicks - lastInputTicks));
    }
}
