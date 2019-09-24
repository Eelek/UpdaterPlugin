package tk.projectcraftmc.updater;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

@SuppressWarnings("unchecked")
public class UpdaterMain extends JavaPlugin implements Listener {

	private String lastData;
	
	private Runnable runnable;
	
	private JSONArray left;
	private JSONArray joined;
	private JSONArray chat;
	
	private int rateLimitCheck;
	private boolean rateLimited;
	
	private Mapper mapper;
	
	private boolean cancel;
	private int currentTaskId;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		getConfig().options().copyDefaults(true);

		mapper = new Mapper(this);

		rateLimitCheck = 0;
		rateLimited = false;

		getServer().getPluginManager().registerEvents(this, this);

		left = new JSONArray();
		joined = new JSONArray();
		chat = new JSONArray();

		lastData = "";

		cancel = false;
		
		runnable = new Runnable() {
			public void run() {
				JSONObject data = new JSONObject();
				JSONArray players = new JSONArray();

				JSONArray currentLeft = left;
				JSONArray currentJoined = joined;
				JSONArray currentChat = chat;

				for (Player p : getServer().getOnlinePlayers()) {
					JSONObject player = new JSONObject();
					player.put("username", p.getPlayerListName());
					player.put("uuid", p.getUniqueId().toString());
					player.put("x", Integer.valueOf(p.getLocation().getBlockX()));
					player.put("y", Integer.valueOf(p.getLocation().getBlockY()));
					player.put("z", Integer.valueOf(p.getLocation().getBlockZ()));
					player.put("dim", p.getWorld().getEnvironment().toString());
					players.add(player);
				}

				data.put("players", players);
				data.put("left", currentLeft);
				data.put("joined", currentJoined);
				if (getConfig().getBoolean("send-chat"))
					data.put("chat", currentChat);

				data.put("current-time", Long.valueOf(System.currentTimeMillis()));
				data.put("refresh-time", Integer.valueOf(getConfig().getInt("delay-time")));

				try {
					if (!rateLimited && !data.toJSONString().equals(lastData)) {
						sendDataToWebserver("data=" + data.toJSONString(), getConfig().getString("api-url"));
						lastData = data.toJSONString();
					} else {
						if (getConfig().getBoolean("logging")) {
							getLogger().info("Rate-limit mode active, not sending data. (Rate-limit counter: " + rateLimitCheck + ").");
						}

						rateLimitCheck = rateLimitCheck - 1;

						if (rateLimitCheck == 0) {
							rateLimited = false;
						}
					}
				} catch (IOException e) {
					e.printStackTrace();
				}

				left.removeAll(currentLeft);
				joined.removeAll(currentJoined);
				chat.removeAll(currentChat);

				if (System.currentTimeMillis() - getConfig().getLong("last-render-update") > (getConfig().getInt("render-update-time") * 1000)) {
					mapper.updateMap();
				}

				if (System.currentTimeMillis() - getConfig().getLong("last-memory-clean") > (getConfig().getInt("memory-clean-update-time") * 1000)) {
					mapper.saveEditedChunks();
				}

				if (cancel) {
					getServer().getScheduler().cancelTask(currentTaskId);
				}
			}
		};

		if (!(new File(getDataFolder() + "/chunkCache.json")).exists()) {
			saveResource("chunkCache.json", false);
		}
	}

	public void onDisable() {
		getServer().getScheduler().cancelTasks(this);
	}

	public void sendDataToWebserver(String data, String URL) throws IOException {
		URL serverURL = new URL(URL);
		HttpsURLConnection conn = (HttpsURLConnection) serverURL.openConnection();

		conn.setRequestMethod("POST");

		conn.setRequestProperty("User-Agent", "Mozilla/5.0");

		conn.setDoOutput(true);

		DataOutputStream out = new DataOutputStream(conn.getOutputStream());

		out.writeBytes(data);

		out.flush();

		out.close();
		
		if(conn.getResponseCode() > 200) {
			throw new ConnectException("Couldn't send data to the webserver. (" + conn.getResponseCode() + " " + conn.getResponseMessage() + ")");
		}

		conn.disconnect();
	}

	public String getDataFromWebserver(String URL) throws IOException {
		URL serverURL = new URL(URL);
		HttpsURLConnection conn = (HttpsURLConnection) serverURL.openConnection();

		conn.setRequestMethod("GET");
		conn.setRequestProperty("User-Agent", "Mozilla/5.0");

		if (conn.getResponseCode() > 200) {
			throw new ConnectException("Couldn't get data from the webserver. (" + conn.getResponseCode() + " " + conn.getResponseMessage() + ")");
		}

		String input = "";

		BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));

		StringBuffer response = new StringBuffer();
		
		while((input = in.readLine()) != null) {
			response.append(input);
		}

		in.close();

		return response.toString();
	}

	@EventHandler
	public void onJoin(PlayerJoinEvent e) {
		JSONObject player = new JSONObject();
		player.put("username", e.getPlayer().getPlayerListName());
		player.put("uuid", e.getPlayer().getUniqueId().toString());
		joined.add(player);

		if (getServer().getOnlinePlayers().size() == 1) {
			getServer().getScheduler().cancelTasks(this);

			currentTaskId = getServer().getScheduler().scheduleSyncRepeatingTask(this, runnable, 100L, getConfig().getInt("delay-time") * 20L);
		}
	}

	@EventHandler
	public void onLeave(PlayerQuitEvent e) {
		JSONObject player = new JSONObject();
		player.put("username", e.getPlayer().getPlayerListName());
		player.put("uuid", e.getPlayer().getUniqueId().toString());
		left.add(player);

		if (getServer().getOnlinePlayers().size() == 1) {
			cancel = true;
		}
	}

	@EventHandler
	public void onChat(AsyncPlayerChatEvent e) {
		JSONObject chatMessage = new JSONObject();
		chatMessage.put("sender", e.getPlayer().getPlayerListName());
		chatMessage.put("time", Long.valueOf(System.currentTimeMillis()));
		chatMessage.put("message", e.getMessage());
		chat.add(chatMessage);
	}

	@EventHandler
	public void onBlockBreak(BlockBreakEvent e) {
		mapper.registerChunk(e.getBlock());
	}

	@EventHandler
	public void onBlockPlace(BlockPlaceEvent e) {
		mapper.registerChunk(e.getBlock());
	}

	public boolean onCommand(CommandSender sender, Command cmd, String commandlabel, String[] args) {
		if (cmd.getName().equalsIgnoreCase("genmap")) {
			if (sender instanceof Player) {
				Player p = (Player) sender;
				mapper.registerChunk(p.getLocation().getBlock());
				mapper.updateMap();
			} else {
				sender.sendMessage("player only");
			}
		}

		return false;
	}
}
