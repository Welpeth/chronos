package com.chronos.tracker.ui;

import com.chronos.tracker.config.I18n;
import com.chronos.tracker.tracking.TaskView;
import com.chronos.tracker.tracking.TrackingEngine.Snapshot;

import java.awt.AWTException;
import java.awt.EventQueue;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Objects;

/**
 * Ícone do Chronos na bandeja do Windows (área de notificação). Clicar abre a janela; o botão direito mostra
 * as tasks que estão contando, com Pausar e Finalizar, além de Pausar todas e Sair: no {@link TrayMenu}, com o
 * visual do app, ou no menu padrão do Windows se não houver um {@link MenuPresenter}.
 *
 * <p>Usa o {@link SystemTray} do AWT; todas as mudanças no ícone rodam na thread do AWT.
 */
public final class TrayIconController {

    /** O que o menu da bandeja faz. Chamado na thread do AWT: quem implementa troca de thread se precisar. */
    public interface Actions {
        void open();

        void pause(String issueKey);

        void finish(String issueKey);

        void pauseAll();

        void exit();
    }

    private final Actions actions;
    private TrayIcon trayIcon;
    private boolean installed;
    private List<String> menuState = List.of();
    private int iconSize = 16;
    private boolean badge;
    /** Menu do botão direito no visual do app; sem ele, vale o menu padrão do Windows. */
    private volatile MenuPresenter presenter;

    /** Abre o menu do botão direito no ponto da tela do clique. Chamado na thread do AWT. */
    public interface MenuPresenter {
        void show(double screenX, double screenY);
    }

    public TrayIconController(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /** Troca o menu cinza do Windows pelo menu do app. Chamar antes de {@link #install()}. */
    public void setMenuPresenter(MenuPresenter menu) {
        presenter = menu;
    }

    /** Coloca o ícone na bandeja; devolve false se o sistema não tem bandeja. */
    public boolean install() {
        if (!SystemTray.isSupported()) {
            return false;
        }
        try {
            SystemTray tray = SystemTray.getSystemTray();
            iconSize = tray.getTrayIconSize().width > 16 ? 32 : 16;
            TrayIcon icon = new TrayIcon(AppIcons.awtLogo(iconSize, false), "Chronos");
            icon.setImageAutoSize(true);
            icon.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getButton() == MouseEvent.BUTTON1) {
                        actions.open();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    MenuPresenter menu = presenter;
                    if (menu != null && (e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3)) {
                        menu.show(e.getXOnScreen(), e.getYOnScreen());
                    }
                }
            });
            if (presenter == null) {
                icon.setPopupMenu(buildMenu(List.of()));
            }
            tray.add(icon);
            trayIcon = icon;
            installed = true;
            return true;
        } catch (AWTException | UncheckedIOException | UnsupportedOperationException | SecurityException e) {
            return false;
        }
    }

    /** Se o ícone chegou a ser colocado na bandeja (mesmo que já tenha sido removido). */
    public boolean wasInstalled() {
        return installed;
    }

    /** Atualiza o menu e a dica do ícone com as tasks que estão contando. */
    public void update(Snapshot snapshot) {
        if (trayIcon == null) {
            return;
        }
        List<TaskView> running = snapshot.tasks().stream().filter(TaskView::running).toList();
        List<String> state = running.stream().map(TrayIconController::menuLabel).toList();
        String tooltip = tooltip(running.size());
        EventQueue.invokeLater(() -> {
            if (trayIcon == null) {
                return;
            }
            trayIcon.setToolTip(tooltip);
            if (presenter == null && !state.equals(menuState)) {
                menuState = state;
                trayIcon.setPopupMenu(buildMenu(running));
            }
        });
    }

    /** Balão de notificação do Windows. */
    public void notify(String title, String message) {
        if (trayIcon == null) {
            return;
        }
        EventQueue.invokeLater(() -> {
            if (trayIcon != null) {
                trayIcon.displayMessage(title, message, TrayIcon.MessageType.INFO);
            }
        });
    }

    /** Liga ou desliga a bolinha vermelha de aviso no ícone. */
    public void setBadge(boolean on) {
        if (trayIcon == null) {
            return;
        }
        EventQueue.invokeLater(() -> {
            if (trayIcon != null && badge != on) {
                badge = on;
                trayIcon.setImage(AppIcons.awtLogo(iconSize, on));
            }
        });
    }

    /** Aviso de task nova: balão de notificação com ícone de alerta. */
    public void alert(String title, String message) {
        if (trayIcon == null) {
            return;
        }
        EventQueue.invokeLater(() -> {
            if (trayIcon != null) {
                trayIcon.displayMessage(title, message, TrayIcon.MessageType.WARNING);
            }
        });
    }

    /** Tira o ícone da bandeja e espera terminar, para o app poder encerrar logo em seguida. */
    public void remove() {
        Runnable removal = () -> {
            if (trayIcon != null) {
                SystemTray.getSystemTray().remove(trayIcon);
                trayIcon = null;
            }
        };
        if (EventQueue.isDispatchThread()) {
            removal.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(removal);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException e) {
            // Encerrando: se não deu para tirar o ícone, o Windows tira quando o processo acabar.
        }
    }

    private PopupMenu buildMenu(List<TaskView> running) {
        PopupMenu menu = new PopupMenu();
        menu.add(item(I18n.t("Abrir o Chronos"), actions::open));
        menu.addSeparator();
        if (running.isEmpty()) {
            MenuItem none = new MenuItem(I18n.t("Nenhuma task contando"));
            none.setEnabled(false);
            menu.add(none);
        } else {
            for (TaskView task : running) {
                Menu submenu = new Menu(menuLabel(task));
                submenu.add(item(I18n.t("Pausar"), () -> actions.pause(task.key())));
                submenu.add(item(I18n.t("Finalizar (mover para Concluído)"), () -> actions.finish(task.key())));
                menu.add(submenu);
            }
            menu.addSeparator();
            menu.add(item(I18n.t("Pausar todas"), actions::pauseAll));
        }
        menu.addSeparator();
        menu.add(item(I18n.t("Sair"), actions::exit));
        return menu;
    }

    private static MenuItem item(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** Texto da task no menu: chave e começo do título. */
    static String menuLabel(TaskView task) {
        String summary = task.summary().strip();
        if (summary.length() > 40) {
            summary = summary.substring(0, 39) + "…";
        }
        return summary.isEmpty() ? task.key() : task.key() + " — " + summary;
    }

    static String tooltip(int running) {
        return switch (running) {
            case 0 -> I18n.t("Chronos · nenhuma task contando");
            case 1 -> I18n.t("Chronos · 1 task contando");
            default -> I18n.t("Chronos · {0} tasks contando", running);
        };
    }
}
