package com.chronos.tracker.system;

import com.chronos.tracker.config.I18n;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Win32Exception;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Abrir o Chronos ao entrar no Windows, pela chave {@code Run} do usuário no registro
 * ({@code HKCU\Software\Microsoft\Windows\CurrentVersion\Run}). Não precisa de administrador.
 */
public final class WindowsStartup {

    static final String RUN_PATH = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static final String VALUE_NAME = "Chronos";
    /** Argumento passado quando o Windows abre o app: começa minimizado. */
    public static final String BACKGROUND_ARG = "--background";

    private WindowsStartup() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    }

    /**
     * O executável do Chronos, se o app está rodando pelo executável instalado. Rodando pelo Maven (java.exe)
     * não há o que registrar.
     */
    public static Optional<Path> executable() {
        return ProcessHandle.current().info().command()
                .map(Path::of)
                .filter(path -> {
                    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                    return !name.equals("java.exe") && !name.equals("javaw.exe")
                            && !name.equals("java") && !name.equals("javaw");
                });
    }

    /** Dá para ligar a opção: Windows e rodando pelo executável. */
    public static boolean isAvailable() {
        return isWindows() && executable().isPresent();
    }

    public static boolean isEnabled() {
        if (!isWindows()) {
            return false;
        }
        try {
            return Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_PATH, VALUE_NAME);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Grava ou apaga o valor direto pela API do registro. Pelo {@code reg.exe}, as aspas em volta do caminho do
     * executável se perdiam na linha de comando e o Windows recusava com código 1.
     */
    public static void setEnabled(boolean enabled) throws IOException {
        if (!isWindows()) {
            throw new IOException(I18n.t("Disponível só no Windows."));
        }
        try {
            if (enabled) {
                Path exe = executable().orElseThrow(() ->
                        new IOException(I18n.t("Abra o Chronos pelo executável instalado para usar esta opção.")));
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_PATH, VALUE_NAME, command(exe));
            } else if (isEnabled()) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_PATH, VALUE_NAME);
            }
        } catch (Win32Exception e) {
            throw new IOException(I18n.t("O Windows recusou a alteração (código {0}).", e.getErrorCode()), e);
        }
    }

    /** O que fica gravado no registro: o executável entre aspas e o argumento de abrir minimizado. */
    static String command(Path exe) {
        return "\"" + exe.toAbsolutePath() + "\" " + BACKGROUND_ARG;
    }
}
