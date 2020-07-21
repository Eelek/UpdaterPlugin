package party.eelek.updater;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.JSONArray;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class Watchdog implements Listener {
	
	private UpdaterMain plugin;
	private ArrayList<int[]> updateBuffer;
	private Runnable watchdog;
	private BukkitTask watchdogTask;
	
	/**
	 * Watchdog constructor.
	 * @param instance An instance of the UpdaterMain class.
	 */
	public Watchdog(UpdaterMain instance) {
		this.plugin = instance;
		this.updateBuffer = new ArrayList<int[]>();
		this.watchdog = new Runnable() {
			
			@Override
			public void run() {
				if(plugin.updating) return;
				try {
					saveChunkCache();
					System.gc();
				} catch(Exception e) {
					plugin.getLogger().severe("An error occured whilst trying to update chunk cache.");
					e.printStackTrace();
					startWatchdog();
				}
			}
		};
		
		if(plugin.getConfig().getBoolean("use-memory-management")) startWatchdog();
	}
	
	/**
	 * Start the Watchdog task.
	 */
	private void startWatchdog() {
		if(watchdogTask != null) watchdogTask.cancel();
		long delaytime = plugin.getConfig().getInt("memory-clean-update-time") * 20L;
		watchdogTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, watchdog, delaytime / 2L, delaytime);
	}
	
	@EventHandler (priority = EventPriority.MONITOR)
	public void onBlockBreak(BlockBreakEvent e) {
		registerBlock(e.getBlock());
	}
	
	@EventHandler (priority = EventPriority.MONITOR)
	public void onBlockPlace(BlockPlaceEvent e) {
		registerBlock(e.getBlock());
	}
	
	public void registerBlock(Block b) {
		registerBlock(b.getX(), b.getZ());
	}
	
	public void registerBlock(int x, int z) {
		int[] tmp = new int[] {x, z};
		if(!updateBuffer.contains(tmp)) updateBuffer.add(tmp);
	}
	
	public ArrayList<int[]> getUpdateBuffer() {
		return updateBuffer;
	}
	
	@SuppressWarnings("unchecked")
	public void saveChunkCache() throws IOException, ParseException {
		if(updateBuffer.isEmpty()) return;
		
		if(plugin.broadcast) plugin.getServer().broadcastMessage(plugin.PREFIX + ChatColor.RED + "Writing memory to file. This may be laggy.");

		JSONArray cacheJSON = getChunkCache();
		ArrayList<int[]> copy = updateBuffer;

		for (int[] b : copy) {
		    if(cacheJSON.contains(b)) continue;
		    
		    cacheJSON.add(b);
		}

		writeChunkCache(cacheJSON, false);

		updateBuffer.removeAll(copy);
		
		copy = null;
		
		if(plugin.broadcast) plugin.getServer().broadcastMessage(plugin.PREFIX + ChatColor.GREEN + "Memory clear complete.");
	}
	
	/**
	 * Clears the chunk cache.
	 * @throws IOException Throws an IOException if writing to disk fails.
	 */
	public void clearChunkCache() throws IOException {
		writeChunkCache(new JSONArray(), false);
		updateBuffer.clear();
		System.gc();
	}
	
	/**
	 * Get the chunkCache.json file
	 * @return The contents of the chunkCache.json file.
	 * @throws FileNotFoundException Throws a FileNotFoundException if chunkCache.json doesn't exists.
	 * @throws IOException Throws an IOException if reading from chunkCache.json fails.
	 * @throws ParseException Throws a ParseException if JSON is invalid.
	 */
	private JSONArray getChunkCache() throws FileNotFoundException, IOException, ParseException {
		JSONParser parser = new JSONParser();

		Object file = parser.parse(new FileReader(plugin.getDataFolder() + "/chunkCache.json"));
		
		return (JSONArray) file;
	}
	
	/**
	 * Writes data to chunkCache.json
	 * @param data The data to be written.
	 * @param append Append the data to the file.
	 * @throws IOException Throws an IOException if writing to disk fails.
	 */
	private void writeChunkCache(JSONArray data, boolean append) throws IOException {
		FileWriter cacheFile = new FileWriter(plugin.getDataFolder() + "/chunkCache.json", append);
		cacheFile.write(data.toJSONString());
		cacheFile.close();
	}
}