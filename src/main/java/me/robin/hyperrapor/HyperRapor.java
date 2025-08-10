package me.robin.hyperrapor;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class HyperRapor extends JavaPlugin implements Listener, CommandExecutor {

    // --- DB ---
    private Connection connection;
    private boolean mysqlEnabled;
    private boolean sqliteMode;

    // --- Config & Messages ---
    private YamlConfiguration messages;
    private String serverName;
    private int dailyLimit;
    private int cooldownMinutes;
    private int messagesToShow;
    private List<String> approvalCommands;
    private String adminPermission;

    // --- Runtime state ---
    private Map<UUID, Integer> dailyReports = new HashMap<>();
    private Map<UUID, Long> lastReportTime = new HashMap<>();
    private Map<UUID, String> reportTargets = new HashMap<>(); // oyuncu -> hedef isim

    // Pending actions (player chatting as input for a flow)
    private enum PendingType { OTHER_REASON, APPROVE_DURATION, APPROVE_REASON, REJECT_REASON }
    private static class PendingAction {
        PendingType type;
        int reportId; // hangi rapora ait
        long durationMillis; // geçici saklama
        String ceza; // ceza türü (susturma/ban/uzaklaştırma)
        PendingAction(PendingType t){ this.type = t; }
    }
    private Map<UUID, PendingAction> pending = new HashMap<>();

    // GUI constants (titles are loaded from messages when available)
    private String guiReportTitle;
    private String guiAdminTitle;
    private String guiDetailTitle;
    private String guiDetailApprove;
    private String guiDetailReject;
    private String guiDetailApproveLore;
    private String guiDetailRejectLore;
    private String guiHistoryTitle;
    private String guiStatsTitle;

    // categories (default; messages.yml override possible)
    private List<String> categories;

    private final int ADMIN_GUI_SIZE = 54; // as requested (6 rows)

    @Override
    public void onEnable(){
        try {
    Class.forName("org.sqlite.JDBC");
} catch (ClassNotFoundException e) {
    getLogger().severe("SQLite JDBC sürücüsü yüklenemedi!");
    e.printStackTrace();
        }

        System.out.println("approve messsage: " + getMsg(guiDetailApprove, ""));
        // ensure plugin folder
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        // save default messages.yml and config.yml if not exist (these resources should be packaged in jar;
        // since we are giving files to you separately, just ensure they exist if not)
        if (!new File(getDataFolder(), "messages.yml").exists()) {
            saveResource("messages.yml", false);
        }
        if (!new File(getDataFolder(), "config.yml").exists()) {
            saveResource("config.yml", false);
        }

        messages = YamlConfiguration.loadConfiguration(new File(getDataFolder(), "messages.yml"));
        reloadConfig(); // load config.yml
        loadSettings();

        getCommand("rapor").setExecutor(this);
        getCommand("raporlar").setExecutor(this);

        // register events
        getServer().getPluginManager().registerEvents(this, this);

        // setup DB
        setupDatabase();

        // daily reset task (runs every 24 hours)
        new BukkitRunnable(){
            @Override
            public void run(){
                dailyReports.clear();
            }
        }.runTaskTimerAsynchronously(this, 20L*60*60*0, 20L*60*60*24); // start immediate, every 24h

        getLogger().info("HyperRapor yüklendi.");
    }

    @Override
    public void onDisable(){
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (Exception ignored) {}
    }

    private void loadSettings(){
        // config.yml keys expected:
        // server-name, limits.daily, limits.cooldownMinutes, messagesToShow, mysql.enabled etc
        mysqlEnabled = getConfig().getBoolean("mysql.enabled", false);
        serverName = getConfig().getString("server-name", "Sunucu");
        dailyLimit = getConfig().getInt("limits.dailyReports", 5);
        cooldownMinutes = getConfig().getInt("limits.cooldownMinutes", 10);
        messagesToShow = getConfig().getInt("messagesToShow", 5);
        approvalCommands = getConfig().getStringList("onay-komutlari");
        adminPermission = getConfig().getString("admin-permission", "hyperrapor.staff");

        // messages
        guiReportTitle = getMsg("gui.report.title", "&cRapor Kategorisi Seç");
        guiAdminTitle = getMsg("gui.admin.title", "&cBekleyen Raporlar - Sayfa");
        guiDetailTitle = getMsg("gui.detail.title", "&6Rapor Detayı #");
        guiDetailApprove = getMsg("gui.detail.approve", "✔ Onayla");
        guiDetailReject = getMsg("gui.detail.reject", "✖ Reddet");
        guiDetailApproveLore = getMsg("gui.detail.approve.lore", "Raporu onaylamak için tıklayın.");
        guiDetailRejectLore = getMsg("gui.detail.reject.lore", "Raporu reddetmek için tıklayın.");
        guiHistoryTitle = getMsg("gui.history.title", "&6Geçmiş Raporlar - Sayfa %page%");
        guiStatsTitle = getMsg("gui.stats.title", "&eRapor İstatistikleri");

        // categories (from messages.yml or default)
        if (messages.contains("categories") && messages.getConfigurationSection("categories") != null) {
            List<String> c = messages.getStringList("categories.list");
            if (c != null && !c.isEmpty()) categories = c;
            else categories = Arrays.asList("Küfür/Hakaret", "Hile", "Spam/Flood", "Reklam", "Diğer");
        } else {
            categories = Arrays.asList("Küfür/Hakaret", "Hile", "Spam/Flood", "Reklam", "Diğer");
        }
    }

    private String getMsg(String path, String def){
        if (messages == null) return ChatColor.translateAlternateColorCodes('&', def);
        String s = messages.getString(path, def);
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    // --------- Database setup ----------
    private void setupDatabase(){
        try {
            if (mysqlEnabled){
                // load mysql connection info
                String host = getConfig().getString("mysql.host", "localhost");
                int port = getConfig().getInt("mysql.port", 3306);
                String db = getConfig().getString("mysql.database", "hyperrapor");
                String user = getConfig().getString("mysql.user", "root");
                String pass = getConfig().getString("mysql.password", "");
                // MySQL driver used by server
                connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + db + "?autoReconnect=true&useSSL=false","" + user, "" + pass);
                sqliteMode = false;
                createTablesMySQL();
            } else {
                File dbFile = new File(getDataFolder(), "database.db");
                String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
                connection = DriverManager.getConnection(url);
                sqliteMode = true;
                createTablesSQLite();
            }
            getLogger().info("Veritabanı başarıyla bağlandı. (MySQL=" + mysqlEnabled + ", SQLite=" + sqliteMode + ")");
        } catch (Exception e){
            getLogger().severe("Veritabanı bağlantı hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTablesMySQL(){
        try {
            Statement st = connection.createStatement();
            // raporlar
            st.execute("CREATE TABLE IF NOT EXISTS raporlar (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "raporlayan VARCHAR(36)," +
                    "raporlanan VARCHAR(64)," +
                    "sunucu VARCHAR(64)," +
                    "zaman BIGINT," +
                    "durum VARCHAR(16)," +
                    "sebep TEXT," +
                    "ceza VARCHAR(32)," +
                    "sure BIGINT," +
                    "onaylayan VARCHAR(36)," +
                    "onay_zaman BIGINT" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
            // chatlogs
            st.execute("CREATE TABLE IF NOT EXISTS chatlogs (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "uuid VARCHAR(36)," +
                    "mesaj TEXT," +
                    "zaman BIGINT" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
            // rapor_logs (history of actions on reports)
            st.execute("CREATE TABLE IF NOT EXISTS rapor_logs (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "rapor_id INT," +
                    "action VARCHAR(16)," +
                    "yetkili VARCHAR(36)," +
                    "sebep TEXT," +
                    "sure BIGINT," +
                    "ceza VARCHAR(32)," +
                    "zaman BIGINT" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;");
            st.close();
        } catch (Exception e){
            getLogger().severe("MySQL tablo oluşturma hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTablesSQLite(){
        try {
            Statement st = connection.createStatement();
            st.execute("CREATE TABLE IF NOT EXISTS raporlar (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "raporlayan TEXT," +
                    "raporlanan TEXT," +
                    "sunucu TEXT," +
                    "zaman INTEGER," +
                    "durum TEXT," +
                    "sebep TEXT," +
                    "ceza TEXT," +
                    "sure INTEGER," +
                    "onaylayan TEXT," +
                    "onay_zaman INTEGER" +
                    ");");
            st.execute("CREATE TABLE IF NOT EXISTS chatlogs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "uuid TEXT," +
                    "mesaj TEXT," +
                    "zaman INTEGER" +
                    ");");
            st.execute("CREATE TABLE IF NOT EXISTS rapor_logs (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "rapor_id INTEGER," +
                    "action TEXT," +
                    "yetkili TEXT," +
                    "sebep TEXT," +
                    "sure INTEGER," +
                    "ceza TEXT," +
                    "zaman INTEGER" +
                    ");");
            st.close();
        } catch (Exception e){
            getLogger().severe("SQLite tablo oluşturma hatası: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ---------- Utilities ----------
    private void runAsync(Runnable r){
        getServer().getScheduler().runTaskAsynchronously(this, r);
    }
    private void runSync(Runnable r){
        getServer().getScheduler().runTask(this, r);
    }

    private long parseDurationToMillis(String input){
        // Accept forms: <number>[s|m|h|d] or "perm"/"kalıcı"
        if (input == null) return 0;
        input = input.trim().toLowerCase();
        if (input.equals("perm") || input.equals("permanent") || input.equals("kalıcı") || input.equals("kalici")) return -1L;
        try {
            if (input.endsWith("ms")) {
                long v = Long.parseLong(input.substring(0, input.length()-2));
                return v;
            } else if (input.endsWith("s")) {
                long v = Long.parseLong(input.substring(0, input.length()-1));
                return v * 1000L;
            } else if (input.endsWith("m")) {
                long v = Long.parseLong(input.substring(0, input.length()-1));
                return v * 60L * 1000L;
            } else if (input.endsWith("h")) {
                long v = Long.parseLong(input.substring(0, input.length()-1));
                return v * 60L * 60L * 1000L;
            } else if (input.endsWith("d")) {
                long v = Long.parseLong(input.substring(0, input.length()-1));
                return v * 24L * 60L * 60L * 1000L;
            } else if (input.endsWith("w")) {
                long v = Long.parseLong(input.substring(0, input.length()-1));
                return v * 7L * 24L * 60L * 60L * 1000L;
            } else {
                // treat as minutes
                long v = Long.parseLong(input);
                return v * 60L * 1000L;
            }
        } catch (Exception e){
            return 0;
        }
    }

    private String formatDurationHuman(long millis){
        if (millis < 0) return "kalıcı";
        if (millis == 0) return "0";
        long seconds = millis / 1000L;
        if (seconds < 60) return seconds + " saniye";
        long minutes = seconds / 60;
        if (minutes < 60) return minutes + (minutes==1?" dakika":" dakika");
        long hours = minutes / 60;
        if (hours < 24) return hours + (hours==1?" saat":" saat");
        long days = hours / 24;
        if (days < 30) return days + (days==1?" gün":" gün");
        long months = days / 30;
        return months + (months==1?" ay":" ay");
    }

    private String timeStampToString(long ts){
        java.sql.Date d = new java.sql.Date(ts);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return sdf.format(d);
    }

    // ---------- Commands ----------
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args){
        if (!(sender instanceof Player)) {
            sender.sendMessage("Sadece oyuncular kullanabilir.");
            return true;
        }
        Player p = (Player) sender;
        if (command.getName().equalsIgnoreCase("rapor")){
            if (args.length != 1){
                p.sendMessage(getMsg("messages.usage.report", "&cKullanım: /rapor <isim>"));
                return true;
            }
            String target = args[0];
            // limit & cooldown checks
            int used = dailyReports.getOrDefault(p.getUniqueId(), 0);
            if (used >= dailyLimit){
                p.sendMessage(getMsg("messages.toomany", "&cBugün daha fazla rapor gönderemezsin!"));
                return true;
            }
            long last = lastReportTime.getOrDefault(p.getUniqueId(), 0L);
            if (System.currentTimeMillis() - last < cooldownMinutes * 60L * 1000L){
                p.sendMessage(getMsg("messages.cooldown", "&eBiraz beklemelisin, çok hızlı raporluyorsun."));
                return true;
            }
            // store target and open GUI
            reportTargets.put(p.getUniqueId(), target);
            openReportCategoryGui(p);
            return true;
        } else if (command.getName().equalsIgnoreCase("raporlar")){
            // permission check
            if (!p.hasPermission(adminPermission)){
                p.sendMessage(getMsg("messages.no_perm", "&cBu komutu kullanmak için yetkiniz yok."));
                return true;
            }
            openAdminGui(p, 0, "bekliyor"); // page 0, filter "bekliyor"
            return true;
        }
        return false;
    }

    // ---------- Player report GUI ----------
    private void openReportCategoryGui(Player p){
        String title = guiReportTitle;
        Inventory inv = Bukkit.createInventory(null, 9, title);
        for (int i=0;i<categories.size() && i<8;i++){
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            meta.setDisplayName(ChatColor.YELLOW + categories.get(i));
            meta.setLore(Arrays.asList(ChatColor.GRAY + getMsg("gui.report.selectcategory.lore", "Bu kategoriyi seçmek için tıklayın.")));
            it.setItemMeta(meta);
            inv.setItem(i, it);
        }
        // Other slot
        ItemStack other = new ItemStack(Material.WRITTEN_BOOK); // in 1.8 BOOK_AND_QUILL exists; but using WRITTEN_BOOK to be safe? We'll keep BOOK_AND_QUILL
        try { other = new ItemStack(Material.getMaterial("BOOK_AND_QUILL")); } catch (Exception ignored) {}
        ItemMeta m2 = other.getItemMeta();
        m2.setDisplayName(ChatColor.AQUA + getMsg("gui.report.other_name", "Diğer (Sohbete Yaz)"));
        m2.setLore(Arrays.asList(ChatColor.GRAY + getMsg("gui.report.other_lore", "Özel rapor sebebini yazmak için tıklayın.")));
        other.setItemMeta(m2);
        inv.setItem(8, other);
        p.openInventory(inv);
    }

    // ---------- Admin GUI (paginated) ----------
    private void openAdminGui(final Player p, final int page, final String filterStatus){
        final String title = guiAdminTitle.replace("%page%", String.valueOf(page+1));
        // fetch async
        runAsync(() -> {
            try {
                int pageSize = 45;
                int offset = page*pageSize;
                PreparedStatement ps;
                if (mysqlEnabled) {
                    ps = connection.prepareStatement("SELECT id, raporlayan, raporlanan, zaman, sebep FROM raporlar WHERE sunucu = ? AND durum = ? ORDER BY zaman ASC LIMIT ? OFFSET ?");
                } else {
                    ps = connection.prepareStatement("SELECT id, raporlayan, raporlanan, zaman, sebep FROM raporlar WHERE sunucu = ? AND durum = ? ORDER BY zaman ASC LIMIT ? OFFSET ?");
                }
                ps.setString(1, serverName);
                ps.setString(2, filterStatus);
                ps.setInt(3, pageSize);
                ps.setInt(4, offset);
                ResultSet rs = ps.executeQuery();
                // create inventory on main thread
                List<Map<String,Object>> rows = new ArrayList<>();
                while (rs.next()){
                    Map<String,Object> row = new HashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("raporlayan", rs.getString("raporlayan"));
                    row.put("raporlanan", rs.getString("raporlanan"));
                    row.put("zaman", rs.getLong("zaman"));
                    row.put("sebep", rs.getString("sebep"));
                    rows.add(row);
                }
                rs.close(); ps.close();
                runSync(() -> {
                    Inventory inv = Bukkit.createInventory(null, ADMIN_GUI_SIZE, title);
                    int slot = 0;
                    for (Map<String,Object> r : rows){
                        int id = (int) r.get("id");
                        String raporlayan = (String) r.get("raporlayan");
                        String raporlanan = (String) r.get("raporlanan");
                        long zaman = (long) r.get("zaman");
                        String sebep = (String) r.get("sebep");
                        ItemStack book = new ItemStack(Material.BOOK);
                        ItemMeta meta = book.getItemMeta();
                        meta.setDisplayName(ChatColor.GOLD + "Rapor #" + id + " - " + raporlanan);
                        List<String> lore = new ArrayList<>();
                        lore.add(ChatColor.GRAY + "Raporlayan: " + raporlayan);
                        lore.add(ChatColor.GRAY + "Zaman: " + timeStampToString(zaman));
                        lore.add(ChatColor.GRAY + "Sebep: " + (sebep==null?"":(sebep.length()>80?sebep.substring(0,80)+"...":sebep)));
                        meta.setLore(lore);
                        book.setItemMeta(meta);
                        inv.setItem(slot, book);
                        slot++;
                    }
                    // control items
                    // Prev
                    ItemStack prev = new ItemStack(Material.ARROW);
                    ItemMeta pm = prev.getItemMeta();
                    pm.setDisplayName(ChatColor.YELLOW + getMsg("gui.admin.prev", "Önceki"));
                    prev.setItemMeta(pm);
                    inv.setItem(45, prev);
                    // Page info filler
                    ItemStack info = new ItemStack(Material.PAPER);
                    ItemMeta im = info.getItemMeta();
                    im.setDisplayName(ChatColor.GREEN + getMsg("gui.admin.pageinfo", "Sayfa: %page%").replace("%page%", String.valueOf(page+1)));
                    info.setItemMeta(im);
                    inv.setItem(49, info);
                    // Next
                    ItemStack next = new ItemStack(Material.ARROW);
                    ItemMeta nm = next.getItemMeta();
                    nm.setDisplayName(ChatColor.YELLOW + getMsg("gui.admin.next", "Sonraki"));
                    next.setItemMeta(nm);
                    inv.setItem(53, next);
                    // History button
                    ItemStack hist = new ItemStack(Material.BOOK_AND_QUILL);
                    ItemMeta hm = hist.getItemMeta();
                    hm.setDisplayName(ChatColor.AQUA + getMsg("gui.admin.history", "Geçmiş Raporlar"));
                    hist.setItemMeta(hm);
                    inv.setItem(47, hist);
                    // Stats button
                    ItemStack stat = new ItemStack(Material.CHEST);
                    ItemMeta sm = stat.getItemMeta();
                    sm.setDisplayName(ChatColor.GOLD + getMsg("gui.admin.stats", "İstatistikler"));
                    stat.setItemMeta(sm);
                    inv.setItem(51, stat);

                    p.openInventory(inv);
                });
            } catch (Exception e){
                e.printStackTrace();
                p.sendMessage(ChatColor.RED + getMsg("messages.error.generic", "Bir hata oluştu."));
            }
        });
    }

    // ---------- Inventory click handling ----------
    @EventHandler
    public void onInventoryClick(InventoryClickEvent e){
        if (e.getWhoClicked() == null) return;
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();
        e.setCancelled(true);

        // Report category GUI
        if (title.equals(guiReportTitle)){
            ItemStack it = e.getCurrentItem();
            if (it == null || !it.hasItemMeta()) return;
            String name = ChatColor.stripColor(it.getItemMeta().getDisplayName());
            // if it's 'Diğer'
            String otherName = ChatColor.stripColor(getMsg("gui.report.other_name", "Diğer (Sohbete Yaz)"));
            if (name.equalsIgnoreCase(otherName)){
                // set pending for other reason
                pending.put(p.getUniqueId(), new PendingAction(PendingType.OTHER_REASON));
                p.closeInventory();
                p.sendMessage(getMsg("messages.other.prompt", "&eLütfen rapor sebebini sohbete yazın (bu mesaj diğer oyuncular tarafından görülmeyebilir)."));
                return;
            }
            // otherwise it's a category
            String kategori = name;
            p.closeInventory();
            saveReportFromGuiChoice(p, kategori);
            return;
        }

        // Admin GUI
        //if (title.startsWith(ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', guiAdminTitle.split("%page%")[0])).trim())) {
        if(title.equals(guiAdminTitle)){
            // clicked a control
            ItemStack it = e.getCurrentItem();
            if (it == null || !it.hasItemMeta()) return;
            String name = ChatColor.stripColor(it.getItemMeta().getDisplayName());
            // Prev
            if (name.equalsIgnoreCase(ChatColor.stripColor(getMsg("gui.admin.prev", "Önceki")))){
                // extract current page from title
                int curPage = extractPageFromTitle(title);
                int newPage = Math.max(0, curPage-1);
                openAdminGui(p, newPage, "bekliyor");
                return;
            }
            // Next
            if (name.equalsIgnoreCase(ChatColor.stripColor(getMsg("gui.admin.next", "Sonraki")))){
                int curPage = extractPageFromTitle(title);
                int newPage = curPage+1;
                openAdminGui(p, newPage, "bekliyor");
                return;
            }
            // History
            if (name.equalsIgnoreCase(ChatColor.stripColor(getMsg("gui.admin.history", "Geçmiş Raporlar")))){
                openHistoryGui(p, 0);
                return;
            }
            // Stats
            if (name.equalsIgnoreCase(ChatColor.stripColor(getMsg("gui.admin.stats", "İstatistikler")))){
                openStatsGui(p);
                return;
            }
            // Otherwise it's a report book item: parse id from displayName
            if (it.getType() == Material.BOOK){
                String display = ChatColor.stripColor(it.getItemMeta().getDisplayName());
                // Expect "Rapor #id - name"
                int hash = display.indexOf("#");
                if (hash != -1){
                    int dash = display.indexOf(" - ", hash);
                    String idStr;
                    if (dash == -1) idStr = display.substring(hash+1).trim();
                    else idStr = display.substring(hash+1, dash).trim();
                    try {
                        int id = Integer.parseInt(idStr);
                        openDetailGui(p, id);
                    } catch (Exception ex){
                        p.sendMessage(ChatColor.RED + "Rapor ID okunamadı.");
                    }
                }
                return;
            }

        }

        // Detail GUI
        if (title.equals(guiDetailTitle)){
            ItemStack it = e.getCurrentItem();
            if (it == null || !it.hasItemMeta()) return;
            String name = ChatColor.stripColor(it.getItemMeta().getDisplayName());
            // Approve
            if (name.equals(guiDetailApprove)){
                // find report id in title
                int id = extractReportIdFromTitle(title);
                if (id == -1) { p.sendMessage(ChatColor.RED + "Rapor ID bulunamadı."); return; }
                // set pending for duration input
                PendingAction pa = new PendingAction(PendingType.APPROVE_DURATION);
                pa.reportId = id;
                pending.put(p.getUniqueId(), pa);
                p.closeInventory();
                p.sendMessage(getMsg("messages.approve.duration_prompt", "&eOnaylamak için süre girin (ör: 10m, 2h, 1d, 'kalıcı'):"));
                return;
            }
            // Reject
           if (name.equals(guiDetailReject)){
                int id = extractReportIdFromTitle(title);
                if (id == -1) { p.sendMessage(ChatColor.RED + "Rapor ID bulunamadı."); return; }
                PendingAction pa = new PendingAction(PendingType.REJECT_REASON);
                pa.reportId = id;
                pending.put(p.getUniqueId(), pa);
                p.closeInventory();
                p.sendMessage(getMsg("messages.reject.prompt", "&eReddetme sebebini yazın:"));
                return;
            }
            return;
        }

        // History GUI
        if (title.equals(guiHistoryTitle)){
            ItemStack it = e.getCurrentItem();
            if (it == null || !it.hasItemMeta()) return;
            String name = ChatColor.stripColor(it.getItemMeta().getDisplayName());
            // Prev
            if (name.equalsIgnoreCase(ChatColor.stripColor(getMsg("gui.admin.prev", "Önceki")))){
                int curPage = extractPageFromTitle(title);
                int newPage = Math.max(0, curPage-1);
                openHistoryGui(p, newPage);
                return;
            }
            if (name.equalsIgnoreCase(ChatColor.stripColor(getMsg("gui.admin.next", "Sonraki")))){
                int curPage = extractPageFromTitle(title);
                int newPage = curPage+1;
                openHistoryGui(p, newPage);
                return;
            }
            // Click on history item -> open detail
            if (it.getType() == Material.ENCHANTED_BOOK || it.getType()==Material.WRITTEN_BOOK || it.getType()==Material.BOOK){
                String display = ChatColor.stripColor(it.getItemMeta().getDisplayName());
                int hash = display.indexOf("#");
                if (hash != -1){
                    String idStr = display.substring(hash+1).trim();
                    try {
                        int id = Integer.parseInt(idStr);
                        openDetailGui(p, id);
                    } catch (Exception ex){
                        p.sendMessage(ChatColor.RED + "Rapor ID okunamadı.");
                    }
                }
                return;
            }
        }

        // Stats GUI (clicks can be implemented later if needed)
        if (title.equals(guiStatsTitle)){
            p.closeInventory();
            p.sendMessage(getMsg("messages.info.stats_click", "&eİstatistikler gösteriminde tıklama yok."));
            return;
        }

    }

    private int extractPageFromTitle(String title){
        // title contains number; try to find digits
        try {
            String digits = title.replaceAll("[^0-9]", "");
            if (digits.length()==0) return 0;
            return Integer.parseInt(digits)-1;
        } catch (Exception e){ return 0; }
    }

    private int extractReportIdFromTitle(String title){
        // guiDetailTitle was like "Rapor Detayı #", but actual title will be guiDetailTitle + id
        // attempt to get trailing digits
        try {
            String digits = title.replaceAll("[^0-9]", "");
            if (digits.length()==0) return -1;
            return Integer.parseInt(digits);
        } catch (Exception e){ return -1; }
    }

    // ---------- Open report detail ----------
    private void openDetailGui(Player p, int reportId){
        runAsync(() -> {
            try {
                PreparedStatement ps = connection.prepareStatement("SELECT * FROM raporlar WHERE id = ?");
                ps.setInt(1, reportId);
                ResultSet rs = ps.executeQuery();
                if (!rs.next()){
                    rs.close(); ps.close();
                    runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.not_found", "Rapor bulunamadı.")));
                    return;
                }
                String raporlayan = rs.getString("raporlayan");
                String raporlanan = rs.getString("raporlanan");
                long zaman = rs.getLong("zaman");
                String sebep = rs.getString("sebep");
                String durum = rs.getString("durum");
                rs.close(); ps.close();

                // fetch last messages (2 minutes before zaman)
                UUID targetUUID = null;
                try {
                    targetUUID = Bukkit.getOfflinePlayer(raporlanan).getUniqueId();
                } catch (Exception ignored){}
                List<String> lastMessages = new ArrayList<>();
                if (targetUUID != null){
                    PreparedStatement ps2 = connection.prepareStatement("SELECT mesaj, zaman FROM chatlogs WHERE uuid = ? AND zaman BETWEEN ? AND ? ORDER BY zaman ASC");
                    ps2.setString(1, targetUUID.toString());
                    ps2.setLong(2, Math.max(0, zaman - 120000));
                    ps2.setLong(3, zaman);
                    ResultSet rs2 = ps2.executeQuery();
                    while (rs2.next()){
                        long mz = rs2.getLong("zaman");
                        String msg = rs2.getString("mesaj");
                        lastMessages.add("[" + timeStampToString(mz) + "] " + msg);
                    }
                    rs2.close(); ps2.close();
                }

                // build GUI on main thread
                runSync(() -> {
                    String title = ChatColor.stripColor(guiDetailTitle) + reportId;
                    Inventory inv = Bukkit.createInventory(null, 27, title); // 3 rows
                    // Info item
                    ItemStack info = new ItemStack(Material.BOOK);
                    ItemMeta im = info.getItemMeta();
                    im.setDisplayName(ChatColor.GOLD + "Rapor #" + reportId);
                    List<String> lore = new ArrayList<>();
                    lore.add(ChatColor.GRAY + "Raporlayan: " + raporlayan);
                    lore.add(ChatColor.GRAY + "Raporlanan: " + raporlanan);
                    lore.add(ChatColor.GRAY + "Zaman: " + timeStampToString(zaman));
                    lore.add(ChatColor.GRAY + "Durum: " + (durum==null?"":durum));
                    lore.add(ChatColor.GRAY + "Sebep: " + (sebep==null?"":sebep));
                    lore.add("");
                    lore.add(ChatColor.YELLOW + "Son mesajlar (" + messagesToShow + "):");
                    int added = 0;
                    for (int i = Math.max(0, lastMessages.size()-messagesToShow); i < lastMessages.size(); i++){
                        lore.add(ChatColor.GRAY + lastMessages.get(i));
                        added++;
                    }
                    if (added==0) lore.add(ChatColor.GRAY + "Mesaj yok.");
                    im.setLore(lore);
                    info.setItemMeta(im);
                    inv.setItem(13, info);

                    // Approve
                    ItemStack approve = new ItemStack(Material.WOOL,1,(short)5); // green wool
                    try { approve = new ItemStack(Material.getMaterial("WOOL"),1,(short)5); } catch (Exception ignored) {}
                    ItemMeta am = approve.getItemMeta();
                    am.setDisplayName(ChatColor.GREEN + getMsg(guiDetailApprove, "✔ Onayla"));
                    am.setLore(Arrays.asList(ChatColor.GRAY + getMsg("gui.detail.approve.lore", "Raporu onaylamak için tıklayın.")));
                    approve.setItemMeta(am);
                    inv.setItem(11, approve);

                    // Reject
                    ItemStack reject = new ItemStack(Material.WOOL,1,(short)14); // red wool
                    try { reject = new ItemStack(Material.getMaterial("WOOL"),1,(short)14); } catch (Exception ignored) {}
                    ItemMeta rm = reject.getItemMeta();
                    rm.setDisplayName(ChatColor.RED + guiDetailReject){);
                    rm.setLore(Arrays.asList(ChatColor.GRAY + GuiDetailRejectLore));
                    reject.setItemMeta(rm);
                    inv.setItem(15, reject);

                    p.openInventory(inv);
                });

            } catch (Exception e){
                e.printStackTrace();
                runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.generic", "Bir hata oluştu.")));
            }
        });
    }

    // ---------- Save report from GUI choice ----------
    private void saveReportFromGuiChoice(Player p, String kategori){
        final String target = reportTargets.get(p.getUniqueId());
        if (target == null || target.trim().isEmpty()){
            p.sendMessage(ChatColor.RED + getMsg("messages.error.no_target", "Rapor hedefi bulunamadı."));
            return;
        }
        runAsync(() -> {
            try {
                long zaman = System.currentTimeMillis();
                PreparedStatement ps = connection.prepareStatement(
                        (mysqlEnabled ?
                                "INSERT INTO raporlar (raporlayan, raporlanan, sunucu, zaman, durum, sebep) VALUES (?, ?, ?, ?, ?, ?)" :
                                "INSERT INTO raporlar (raporlayan, raporlanan, sunucu, zaman, durum, sebep) VALUES (?, ?, ?, ?, ?, ?)"
                        ), Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, p.getName());
                ps.setString(2, target);
                ps.setString(3, serverName);
                ps.setLong(4, zaman);
                ps.setString(5, "bekliyor");
                ps.setString(6, kategori);
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                int newId = -1;
                if (keys != null && keys.next()){
                    newId = keys.getInt(1);
                    keys.close();
                }
                ps.close();
                // update counters
                dailyReports.put(p.getUniqueId(), dailyReports.getOrDefault(p.getUniqueId(), 0) + 1);
                lastReportTime.put(p.getUniqueId(), System.currentTimeMillis());
                reportTargets.remove(p.getUniqueId());

                runSync(() -> p.sendMessage(ChatColor.GREEN + getMsg("messages.success", "Raporunuz başarıyla gönderildi.")));
                // notify online staff
                runSync(() -> {
                    String notify = ChatColor.translateAlternateColorCodes('&', getMsg("messages.notify_staff", "&6Yeni rapor: &c{hedef} &7tarafından &e{raporlayan}")).replace("{hedef}", target).replace("{raporlayan}", p.getName());
                    for (Player op : Bukkit.getOnlinePlayers()){
                        if (op.hasPermission(adminPermission)){
                            op.sendMessage(notify);
                        }
                    }
                });
            } catch (Exception e){
                e.printStackTrace();
                runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.save", "Rapor kaydedilirken bir hata oluştu.")));
            }
        });
    }

    // ---------- Chat handling for pending inputs and chat logs ----------
    @EventHandler
    public void onPlayerChat(AsyncPlayerChatEvent e){
        Player p = e.getPlayer();
        UUID id = p.getUniqueId();

        // If player has a pending action, intercept the chat and treat as input (do not broadcast)
        if (pending.containsKey(id)){
            e.setCancelled(true);
            PendingAction pa = pending.remove(id);
            if (pa.type == PendingType.OTHER_REASON){
                String reason = e.getMessage();
                // save report with reason
                runAsync(() -> {
                    try {
                        long zaman = System.currentTimeMillis();
                        String target = reportTargets.get(p.getUniqueId());
                        if (target == null) {
                            runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.no_target", "Rapor hedefi bulunamadı.")));
                            return;
                        }
                        PreparedStatement ps = connection.prepareStatement(
                                (mysqlEnabled ?
                                "INSERT INTO raporlar (raporlayan, raporlanan, sunucu, zaman, durum, sebep) VALUES (?, ?, ?, ?, ?, ?)" :
                                "INSERT INTO raporlar (raporlayan, raporlanan, sunucu, zaman, durum, sebep) VALUES (?, ?, ?, ?, ?, ?)"
                                ), Statement.RETURN_GENERATED_KEYS);
                        ps.setString(1, p.getName());
                        ps.setString(2, target);
                        ps.setString(3, serverName);
                        ps.setLong(4, zaman);
                        ps.setString(5, "bekliyor");
                        ps.setString(6, reason);
                        ps.executeUpdate();
                        ResultSet keys = ps.getGeneratedKeys();
                        if (keys != null) keys.close();
                        ps.close();
                        dailyReports.put(p.getUniqueId(), dailyReports.getOrDefault(p.getUniqueId(), 0) + 1);
                        lastReportTime.put(p.getUniqueId(), System.currentTimeMillis());
                        reportTargets.remove(p.getUniqueId());
                        runSync(() -> p.sendMessage(ChatColor.GREEN + getMsg("messages.success", "Raporunuz başarıyla gönderildi.")));
                        // notify staff
                        runSync(() -> {
                            String notify = ChatColor.translateAlternateColorCodes('&', getMsg("messages.notify_staff", "&6Yeni rapor: &c{hedef} &7tarafından &e{raporlayan}"))
                                    .replace("{hedef}", target).replace("{raporlayan}", p.getName());
                            for (Player op : Bukkit.getOnlinePlayers()){
                                if (op.hasPermission(adminPermission)){
                                    op.sendMessage(notify);
                                }
                            }
                        });
                    } catch (Exception ex){
                        ex.printStackTrace();
                        runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.save", "Rapor kaydedilirken bir hata oluştu.")));
                    }
                });
            } else if (pa.type == PendingType.APPROVE_DURATION){
                String durInput = e.getMessage();
                long millis = parseDurationToMillis(durInput);
                if (millis == 0 && !durInput.toLowerCase().contains("kal")) {
                    // invalid
                    p.sendMessage(ChatColor.RED + getMsg("messages.approve.duration_invalid", "Geçersiz süre girdiniz. Örnek: 10m, 2h, 1d veya 'kalıcı'"));
                    // put back pending so they can try again
                    pending.put(id, pa);
                    return;
                }
                // store millis and ask for reason
                pa.durationMillis = millis;
                pa.type = PendingType.APPROVE_REASON;
                pending.put(id, pa);
                p.sendMessage(getMsg("messages.approve.reason_prompt", "&eOnay nedeni yazın (kısa açıklama):"));
            } else if (pa.type == PendingType.APPROVE_REASON){
                String reason = e.getMessage();
                // finalize approval: update rapor, insert log, run commands
                runAsync(() -> {
                    try {
                        // fetch report info
                        PreparedStatement q = connection.prepareStatement("SELECT * FROM raporlar WHERE id = ?");
                        q.setInt(1, pa.reportId);
                        ResultSet r = q.executeQuery();
                        if (!r.next()) { r.close(); q.close(); runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.not_found", "Rapor bulunamadı."))); return; }
                        String reported = r.getString("raporlanan");
                        r.close(); q.close();

                        // update rapor row
                        PreparedStatement up = connection.prepareStatement("UPDATE raporlar SET durum = ?, ceza = ?, sure = ?, onaylayan = ?, onay_zaman = ? WHERE id = ?");
                        up.setString(1, "onaylandi");
                        up.setString(2, pa.ceza==null?"":pa.ceza);
                        up.setLong(3, pa.durationMillis);
                        up.setString(4, p.getName());
                        up.setLong(5, System.currentTimeMillis());
                        up.setInt(6, pa.reportId);
                        up.executeUpdate();
                        up.close();

                        // insert into rapor_logs
                        PreparedStatement ins = connection.prepareStatement("INSERT INTO rapor_logs (rapor_id, action, yetkili, sebep, sure, ceza, zaman) VALUES (?, ?, ?, ?, ?, ?, ?)");
                        ins.setInt(1, pa.reportId);
                        ins.setString(2, "onay");
                        ins.setString(3, p.getName());
                        ins.setString(4, reason);
                        ins.setLong(5, pa.durationMillis);
                        ins.setString(6, pa.ceza==null?"":pa.ceza);
                        ins.setLong(7, System.currentTimeMillis());
                        ins.executeUpdate();
                        ins.close();

                        // run configured commands
                        if (approvalCommands != null && !approvalCommands.isEmpty()){
                            for (String cmdTemplate : approvalCommands){
                                String cmd = cmdTemplate;
                                cmd = cmd.replace("%raporlanan%", reported);
                                cmd = cmd.replace("%onaylayan%", p.getName());
                                cmd = cmd.replace("%süre%", formatDurationHuman(pa.durationMillis));
                                cmd = cmd.replace("%ceza-tür%", pa.ceza==null?"ceza":pa.ceza);
                                final String finalCmd = cmd;
                                runSync(() -> {
                                    getServer().dispatchCommand(getServer().getConsoleSender(), finalCmd);
                                });
                            }
                        }

                        runSync(() -> {
                            p.sendMessage(ChatColor.GREEN + getMsg("messages.approve.success", "Rapor onaylandı ve uygulandı."));
                        });

                    } catch (Exception ex){
                        ex.printStackTrace();
                        runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.generic", "Bir hata oluştu.")));
                    }
                });

            } else if (pa.type == PendingType.REJECT_REASON){
                String reason = e.getMessage();
                // finalize rejection
                runAsync(() -> {
                    try {
                        PreparedStatement up = connection.prepareStatement("UPDATE raporlar SET durum = ?, onaylayan = ?, onay_zaman = ? WHERE id = ?");
                        up.setString(1, "reddedildi");
                        up.setString(2, p.getName());
                        up.setLong(3, System.currentTimeMillis());
                        up.setInt(4, pa.reportId);
                        up.executeUpdate();
                        up.close();

                        PreparedStatement ins = connection.prepareStatement("INSERT INTO rapor_logs (rapor_id, action, yetkili, sebep, sure, ceza, zaman) VALUES (?, ?, ?, ?, ?, ?, ?)");
                        ins.setInt(1, pa.reportId);
                        ins.setString(2, "red");
                        ins.setString(3, p.getName());
                        ins.setString(4, reason);
                        ins.setLong(5, 0);
                        ins.setString(6, "");
                        ins.setLong(7, System.currentTimeMillis());
                        ins.executeUpdate();
                        ins.close();

                        runSync(() -> p.sendMessage(ChatColor.GREEN + getMsg("messages.reject.success", "Rapor reddedildi ve kaydedildi.")));
                    } catch (Exception ex){
                        ex.printStackTrace();
                        runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.generic", "Bir hata oluştu.")));
                    }
                });
            }
            return; // handled
        }

        // If no pending action, still log chat into DB
        // Continue to log chat normally (non-cancelled)
        // Insert into DB
        runAsync(() -> {
            try {
                PreparedStatement ps = connection.prepareStatement("INSERT INTO chatlogs (uuid, mesaj, zaman) VALUES (?, ?, ?)");
                ps.setString(1, p.getUniqueId().toString());
                ps.setString(2, e.getMessage());
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
                ps.close();
            } catch (Exception ex){
                ex.printStackTrace();
            }
        });
    }

    // When player quits: remove pending if any to avoid leaked states
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent e){
        pending.remove(e.getPlayer().getUniqueId());
        reportTargets.remove(e.getPlayer().getUniqueId());
    }

    // ---------- History GUI ----------
    private void openHistoryGui(Player p, int page){
        final int pageSize = 45;
        final int offset = page * pageSize;
        final String title = guiHistoryTitle.replace("%page%", String.valueOf(page+1));
        runAsync(() -> {
            try {
                PreparedStatement ps = connection.prepareStatement("SELECT id, raporlayan, raporlanan, zaman, durum FROM raporlar WHERE sunucu = ? ORDER BY zaman DESC LIMIT ? OFFSET ?");
                ps.setString(1, serverName);
                ps.setInt(2, pageSize);
                ps.setInt(3, offset);
                ResultSet rs = ps.executeQuery();
                List<Map<String,Object>> rows = new ArrayList<>();
                while (rs.next()){
                    Map<String,Object> row = new HashMap<>();
                    row.put("id", rs.getInt("id"));
                    row.put("raporlayan", rs.getString("raporlayan"));
                    row.put("raporlanan", rs.getString("raporlanan"));
                    row.put("zaman", rs.getLong("zaman"));
                    row.put("durum", rs.getString("durum"));
                    rows.add(row);
                }
                rs.close(); ps.close();
                runSync(() -> {
                    Inventory inv = Bukkit.createInventory(null, ADMIN_GUI_SIZE, title);
                    int slot = 0;
                    for (Map<String,Object> r : rows){
                        int id = (int) r.get("id");
                        String raporlayan = (String) r.get("raporlayan");
                        String raporlanan = (String) r.get("raporlanan");
                        long zaman = (long) r.get("zaman");
                        String durum = (String) r.get("durum");
                        ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
                        ItemMeta meta = book.getItemMeta();
                        meta.setDisplayName(ChatColor.GOLD + "Rapor #" + id);
                        List<String> lore = new ArrayList<>();
                        lore.add(ChatColor.GRAY + "Raporlayan: " + raporlayan);
                        lore.add(ChatColor.GRAY + "Raporlanan: " + raporlanan);
                        lore.add(ChatColor.GRAY + "Zaman: " + timeStampToString(zaman));
                        lore.add(ChatColor.GRAY + "Durum: " + durum);
                        meta.setLore(lore);
                        book.setItemMeta(meta);
                        inv.setItem(slot, book);
                        slot++;
                    }
                    // controls
                    ItemStack prev = new ItemStack(Material.ARROW);
                    ItemMeta pm = prev.getItemMeta();
                    pm.setDisplayName(ChatColor.YELLOW + getMsg("gui.admin.prev", "Önceki"));
                    prev.setItemMeta(pm);
                    inv.setItem(45, prev);
                    ItemStack info = new ItemStack(Material.PAPER);
                    ItemMeta im = info.getItemMeta();
                    im.setDisplayName(ChatColor.GREEN + getMsg("gui.admin.pageinfo", "Sayfa: %page%").replace("%page%", String.valueOf(page+1)));
                    info.setItemMeta(im);
                    inv.setItem(49, info);
                    ItemStack next = new ItemStack(Material.ARROW);
                    ItemMeta nm = next.getItemMeta();
                    nm.setDisplayName(ChatColor.YELLOW + getMsg("gui.admin.next", "Sonraki"));
                    next.setItemMeta(nm);
                    inv.setItem(53, next);

                    p.openInventory(inv);
                });
            } catch (Exception e){
                e.printStackTrace();
                runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.generic", "Bir hata oluştu.")));
            }
        });
    }

    // ---------- Stats GUI ----------
    private void openStatsGui(Player p){
        // show top approvers daily/weekly/monthly
        runAsync(() -> {
            try {
                long now = System.currentTimeMillis();
                long dayAgo = now - 24L*60*60*1000;
                long weekAgo = now - 7L*24*60*60*1000;
                long monthAgo = now - 30L*24*60*60*1000;
                // top daily
                PreparedStatement sd = connection.prepareStatement("SELECT yetkili, COUNT(*) as cnt FROM rapor_logs WHERE action = 'onay' AND zaman >= ? GROUP BY yetkili ORDER BY cnt DESC LIMIT 10");
                sd.setLong(1, dayAgo);
                ResultSet rd = sd.executeQuery();
                List<String> dailyTop = new ArrayList<>();
                while (rd.next()){
                    dailyTop.add(rd.getString("yetkili") + " - " + rd.getInt("cnt"));
                }
                rd.close(); sd.close();
                // weekly
                PreparedStatement sw = connection.prepareStatement("SELECT yetkili, COUNT(*) as cnt FROM rapor_logs WHERE action = 'onay' AND zaman >= ? GROUP BY yetkili ORDER BY cnt DESC LIMIT 10");
                sw.setLong(1, weekAgo);
                ResultSet rw = sw.executeQuery();
                List<String> weekTop = new ArrayList<>();
                while (rw.next()){
                    weekTop.add(rw.getString("yetkili") + " - " + rw.getInt("cnt"));
                }
                rw.close(); sw.close();
                // monthly
                PreparedStatement sm = connection.prepareStatement("SELECT yetkili, COUNT(*) as cnt FROM rapor_logs WHERE action = 'onay' AND zaman >= ? GROUP BY yetkili ORDER BY cnt DESC LIMIT 10");
                sm.setLong(1, monthAgo);
                ResultSet rm = sm.executeQuery();
                List<String> monthTop = new ArrayList<>();
                while (rm.next()){
                    monthTop.add(rm.getString("yetkili") + " - " + rm.getInt("cnt"));
                }
                rm.close(); sm.close();

                runSync(() -> {
                    Inventory inv = Bukkit.createInventory(null, 27, guiStatsTitle);
                    ItemStack daily = new ItemStack(Material.PAPER);
                    ItemMeta dm = daily.getItemMeta();
                    dm.setDisplayName(ChatColor.GOLD + "Günlük Top 10");
                    dm.setLore(stringListToColoredLore(dailyTop));
                    daily.setItemMeta(dm);
                    inv.setItem(10, daily);

                    ItemStack weekly = new ItemStack(Material.PAPER);
                    ItemMeta wm = weekly.getItemMeta();
                    wm.setDisplayName(ChatColor.GOLD + "Haftalık Top 10");
                    wm.setLore(stringListToColoredLore(weekTop));
                    weekly.setItemMeta(wm);
                    inv.setItem(13, weekly);

                    ItemStack monthly = new ItemStack(Material.PAPER);
                    ItemMeta mm = monthly.getItemMeta();
                    mm.setDisplayName(ChatColor.GOLD + "Aylık Top 10");
                    mm.setLore(stringListToColoredLore(monthTop));
                    monthly.setItemMeta(mm);
                    inv.setItem(16, monthly);

                    p.openInventory(inv);
                });
            } catch (Exception e){
                e.printStackTrace();
                runSync(() -> p.sendMessage(ChatColor.RED + getMsg("messages.error.generic", "Bir hata oluştu.")));
            }
        });
    }

    private List<String> stringListToColoredLore(List<String> in){
        List<String> out = new ArrayList<>();
        if (in.isEmpty()) out.add(ChatColor.GRAY + "Veri yok.");
        else {
            for (String s : in) out.add(ChatColor.GRAY + s);
        }
        return out;
    }
}
