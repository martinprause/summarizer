package com.summarizer.user.ui;

import com.summarizer.user.User;
import com.summarizer.user.UserRepository;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Admin-View: Benutzer anlegen, sperren, Rolle ändern.
 * Wirksam wird Sperren/Rollen erst mit dem Login (Phase 4) —
 * Datenbasis und Verwaltung stehen ab jetzt bereit.
 */
@Route("users")
@PageTitle("Benutzer — Summarizer Studio")
@RolesAllowed("ADMIN")
public class UsersView extends VerticalLayout {

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.systemDefault());

    private final UserRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final com.summarizer.item.ItemRepository itemRepository;
    private final com.summarizer.base.CurrentUser currentUser;
    private final Grid<User> grid = new Grid<>();

    public UsersView(UserRepository repository, PasswordEncoder passwordEncoder,
                     com.summarizer.item.ItemRepository itemRepository,
                     com.summarizer.base.CurrentUser currentUser) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.itemRepository = itemRepository;
        this.currentUser = currentUser;
        setPadding(true);
        addClassName("fade-in");

        add(new H2(getTranslation("users.title")));
        add(new Paragraph(getTranslation("users.intro")));

        Button create = new Button(getTranslation("users.create"), e -> openCreateDialog());
        create.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        add(create);

        grid.addColumn(User::getUsername).setHeader(getTranslation("users.column.username")).setAutoWidth(true);
        grid.addColumn(User::getEmail).setHeader(getTranslation("users.column.email")).setFlexGrow(1);
        grid.addColumn(User::getAuthProvider).setHeader(getTranslation("users.column.auth")).setAutoWidth(true);
        grid.addColumn(u -> FORMAT.format(u.getCreatedAt()))
                .setHeader(getTranslation("users.column.since")).setAutoWidth(true);
        grid.addComponentColumn(user -> {
            Select<String> role = new Select<>();
            role.setItems("USER", "ADMIN");
            role.setValue(user.getRole());
            role.setWidth("110px");
            role.addValueChangeListener(e -> {
                if (e.isFromClient()) {
                    user.setRole(e.getValue());
                    repository.save(user);
                    Notification.show(getTranslation("users.roleChanged"));
                }
            });
            return role;
        }).setHeader(getTranslation("users.column.role")).setAutoWidth(true);
        grid.addComponentColumn(user -> {
            Button toggle = new Button(user.isLocked()
                    ? getTranslation("users.unlock") : getTranslation("users.lock"), e -> {
                user.setLocked(!user.isLocked());
                repository.save(user);
                refresh();
            });
            toggle.addThemeVariants(ButtonVariant.LUMO_SMALL,
                    user.isLocked() ? ButtonVariant.LUMO_SUCCESS : ButtonVariant.LUMO_ERROR);
            Button password = new Button(getTranslation("users.password"), e -> openPasswordDialog(user));
            password.addThemeVariants(ButtonVariant.LUMO_SMALL);
            Button delete = new Button(getTranslation("users.delete"), e -> confirmDeleteUser(user));
            delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR);
            // Admin-Konto und der eigene Account bleiben geschützt
            delete.setEnabled(!"admin".equals(user.getUsername())
                    && !user.getId().equals(currentUser.id()));
            return new com.vaadin.flow.component.orderedlayout.HorizontalLayout(
                    toggle, password, delete);
        }).setHeader(getTranslation("users.column.actions")).setAutoWidth(true);
        grid.setWidthFull();
        add(grid);
        refresh();
    }

    private void openCreateDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("users.dialog.create"));
        TextField username = new TextField(getTranslation("users.field.username"));
        username.setWidthFull();
        EmailField email = new EmailField(getTranslation("users.field.email"));
        email.setWidthFull();
        Select<String> role = new Select<>();
        role.setLabel(getTranslation("users.field.role"));
        role.setItems("USER", "ADMIN");
        role.setValue("USER");

        Button save = new Button(getTranslation("users.add"), e -> {
            String name = username.getValue().trim();
            if (name.isBlank()) {
                username.setInvalid(true);
                return;
            }
            if (repository.findByUsername(name).isPresent()) {
                username.setInvalid(true);
                username.setErrorMessage(getTranslation("users.usernameExists"));
                return;
            }
            User user = new User(name, role.getValue());
            user.setEmail(email.getValue().isBlank() ? null : email.getValue().trim());
            repository.save(user);
            dialog.close();
            refresh();
            Notification.show(getTranslation("users.created"));
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(username, email, role, save));
        dialog.open();
    }

    /** Benutzer löschen — inklusive aller seiner Inhalte (Kaskade in der DB). */
    private void confirmDeleteUser(User user) {
        if ("admin".equals(user.getUsername())) {
            Notification.show(getTranslation("users.deleteAdmin"));
            return;
        }
        if (user.getId().equals(currentUser.id())) {
            Notification.show(getTranslation("users.deleteSelf"));
            return;
        }
        long items = itemRepository.countByUserId(user.getId());
        com.vaadin.flow.component.confirmdialog.ConfirmDialog dialog =
                new com.vaadin.flow.component.confirmdialog.ConfirmDialog();
        dialog.setHeader(getTranslation("users.delete.header", user.getUsername()));
        dialog.setText(getTranslation("users.delete.text", String.valueOf(items)));
        dialog.setCancelable(true);
        dialog.setCancelText(getTranslation("users.cancel"));
        dialog.setConfirmText(getTranslation("users.delete"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            repository.delete(user);
            refresh();
            Notification.show(getTranslation("users.deleted"));
        });
        dialog.open();
    }

    private void openPasswordDialog(User user) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle(getTranslation("users.password.header", user.getUsername()));
        TextField password = new TextField(getTranslation("users.field.newPassword"));
        password.setWidthFull();
        Button save = new Button(getTranslation("users.save"), e -> {
            if (password.getValue().length() < 8) {
                password.setInvalid(true);
                password.setErrorMessage(getTranslation("users.passwordTooShort"));
                return;
            }
            user.setPasswordHash(passwordEncoder.encode(password.getValue()));
            repository.save(user);
            dialog.close();
            Notification.show(getTranslation("users.passwordSet"));
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        dialog.add(new VerticalLayout(password, save));
        dialog.open();
    }

    private void refresh() {
        grid.setItems(repository.findAll());
    }
}
