package app.castor.shell;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Tiny account list. Each account maps to one isolated WebView profile (cookies, storage, cache). */
final class Accounts {
    static final class Account {
        final String id, name;
        Account(String id, String name) { this.id = id; this.name = name; }
    }

    private final SharedPreferences sp;
    private final List<Account> list = new ArrayList<>();
    private String activeId;

    Accounts(SharedPreferences sp) {
        this.sp = sp;
        for (String line : sp.getString("accounts", "").split("\n")) {
            String[] p = line.split("\t", 2);
            if (p.length == 2 && !p[0].isEmpty()) list.add(new Account(p[0], p[1]));
        }
        if (list.isEmpty()) { list.add(new Account("acc_1", "Account 1")); save(); }
        activeId = sp.getString("active", list.get(0).id);
        if (find(activeId) == null) activeId = list.get(0).id;
    }

    List<Account> all() { return list; }
    int size() { return list.size(); }
    String currentId() { return activeId; }
    Account current() { return find(activeId); }

    Account add(String name) {
        int n = sp.getInt("next", 2);
        sp.edit().putInt("next", n + 1).apply();
        Account a = new Account("acc_" + n, name.replace('\t', ' ').replace('\n', ' ').trim());
        list.add(a);
        save();
        return a;
    }

    void remove(Account a) {
        if (list.size() > 1) { list.remove(a); save(); }
    }

    void setActive(String id) {
        activeId = id;
        sp.edit().putString("active", id).apply();
    }

    private Account find(String id) {
        for (Account a : list) if (a.id.equals(id)) return a;
        return null;
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (Account a : list) sb.append(a.id).append('\t').append(a.name).append('\n');
        sp.edit().putString("accounts", sb.toString()).apply();
    }
}
