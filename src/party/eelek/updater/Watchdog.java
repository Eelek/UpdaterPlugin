package party.eelek.updater;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.scheduler.BukkitTask;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class Watchdog implements Listener {
	
	private UpdaterMain plugin;
	private ArrayList<SuperChunk> chunks;
	private Runnable watchdog;
	private BukkitTask watchdogTask;
	
	/**
	 * Watchdog constructor.
	 * @param instance An instance of the UpdaterMain class.
	 */
	public Watchdog(UpdaterMain instance) {
		this.plugin = instance;
		this.chunks = new ArrayList<SuperChunk>();
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
		
		startWatchdog();
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
		registerChunk(e.getBlock());
	}
	
	@EventHandler (priority = EventPriority.MONITOR)
	public void onBlockPlace(BlockPlaceEvent e) {
		registerChunk(e.getBlock());
	}
	
	/**
	 * Register a chunk after an edit.
	 * @param b The block that was edited.
	 */
	public void registerChunk(Block b) {
		SuperChunk c = getSuperChunk(b.getWorld(), b.getX(), b.getZ());
		if (!containsSuperChunk(chunks, c)) {
			chunks.add(c);
			if(plugin.debugLogging) plugin.getLogger().info("Registered (" + c.getX() + ", " + c.getZ() + ").");
		}
	}
	
	/**
	 * Register a chunk after an edit.
	 * @param w The world in which the chunk was edited.
	 * @param x The X-coordinate of the block that was edited.
	 * @param z The Z-coordinate of the block that was edited.
	 */
	public void registerChunk(World w, int x, int z) {
		SuperChunk c = getSuperChunk(w, x, z);
		if (!containsSuperChunk(chunks, c)) {
			chunks.add(c);
			if(plugin.debugLogging) plugin.getLogger().info("Registered (" + c.getX() + ", " + c.getZ() + ").");
		}
	}
	
	/**
	 * Get a list of SuperChunks that were edited.
	 * @return A list of SuperChunks that were edited.
	 * @throws FileNotFoundException A FileNotFoundException will be thrown if chunkCache.json doesn't exists.
	 * @throws IOException An IOException will be thrown if reading chunkCache.json fails.
	 * @throws ParseException A ParseException will be thrown if parseing chunkCache.json fails.
	 */
	public ArrayList<SuperChunk> getEditedChunks() throws FileNotFoundException, IOException, ParseException {
		ArrayList<SuperChunk> editedChunks = new ArrayList<SuperChunk>();
		
		JSONArray cacheJSON = getChunkCache();
		for(int c = 0; c < cacheJSON.size(); c++) {
			JSONObject chunk = (JSONObject) cacheJSON.get(c);
			editedChunks.add(SuperChunk.deserialize(chunk));
		}
		
		ArrayList<SuperChunk> copy = chunks;
		for(SuperChunk c : copy) {
			if(containsSuperChunk(editedChunks, c)) continue;
			
			editedChunks.add(c);
		}
		
		return editedChunks;
	}
	
	/**
	 * Save all edited chunks stored in memory to disk.
	 * @throws IOException Throws an IOException if writing to disk fails.
	 * @throws ParseException Throws a ParseException if JSON is invalid.
	 */
	@SuppressWarnings("unchecked")
	public void saveChunkCache() throws IOException, ParseException {
		if(chunks.isEmpty()) return;
		
		if(plugin.broadcast) plugin.getServer().broadcastMessage(plugin.PREFIX + ChatColor.RED + "Writing memory to file. This may be laggy.");

		JSONArray cacheJSON = getChunkCache();
		ArrayList<SuperChunk> copy = chunks;

		for (SuperChunk c : copy) {
		    JSONObject chunk = c.serialize();
		    
		    if(cacheJSON.contains(c)) return;
		    
		    cacheJSON.add(chunk);
		}

		writeChunkCache(cacheJSON, false);

		chunks.removeAll(copy);
		
		if(plugin.broadcast) plugin.getServer().broadcastMessage(plugin.PREFIX + ChatColor.GREEN + "Memory clear complete.");
	}
	
	/**
	 * Clears the chunk cache.
	 * @throws IOException Throws an IOException if writing to disk fails.
	 */
	public void clearChunkCache() throws IOException {
		writeChunkCache(new JSONArray(), false);
		chunks.clear();
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
	
	/**
	 * Get the SuperChunk that a block is in.
	 * @param w The world that the block is in.
	 * @param x The X-coordinate of the block.
	 * @param z The Z-coordinate of the block.
	 * @return The SuperChunk that the block is in.
	 */
	private SuperChunk getSuperChunk(World w, int x, int z) {
		x = Math.floorDiv(x, plugin.CHUNKSIZE) * plugin.CHUNKSIZE;
		z = Math.floorDiv(z, plugin.CHUNKSIZE) * plugin.CHUNKSIZE;
		
		return new SuperChunk(w, x, z);
	}
	
	/**
	 * Check if a SuperChunk is already in a list.
	 * @param l The list that needs to be scanned.
	 * @param w The world that the SuperChunk is in.
	 * @param x The X-coordinate of a block within the SuperChunk.
	 * @param z The Z-coordinate of a block within the SuperChunk.
	 * @return If the SuperChunk is in the list.
	 */
	private boolean containsSuperChunk(List<SuperChunk> l, World w, int x, int z) {
		boolean found = false;
		
		for(SuperChunk c : l) {
			if(c.getWorld() == w && c.getX() == x && c.getZ() == z) {
				found = true;
				break;
			}
		}
		
		return found;
	}
	
	/**
	 * Check if a SuperChunk is already in a list.
	 * @param l The list that needs to be scanned.
	 * @param s The SuperChunk.
	 * @return If the SuperChunk is in the list.
	 */
	private boolean containsSuperChunk(List<SuperChunk> l, SuperChunk s) {
		return containsSuperChunk(l, s.getWorld(), s.getX(), s.getZ());
	}
	
}