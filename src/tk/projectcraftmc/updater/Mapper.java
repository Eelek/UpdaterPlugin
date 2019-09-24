package tk.projectcraftmc.updater;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

public class Mapper {
	
	private UpdaterMain plugin;
	
	private final int WIDTH = 128;
	private final int HEIGHT = 128;
	private HashMap<Material, Integer> colorIndex;
	private ArrayList<Location> editedChunks;

	public Mapper(UpdaterMain instance) {
		plugin = instance;

		editedChunks = new ArrayList<Location>();

		try {
			String colorData = plugin.getDataFromWebserver(plugin.getConfig().getString("map-data-url"));			

			colorIndex = new HashMap<Material, Integer>();

			JSONParser parser = new JSONParser();
			Object object = parser.parse(colorData);
			JSONArray ids = (JSONArray) object;

			for (int i = 0; i < ids.size(); i++) {
				JSONObject id = (JSONObject) ids.get(i);
				JSONArray materials = (JSONArray) id.get("materials");

				for (int m = 0; m < materials.size(); m++) {
					Material material = Material.getMaterial(materials.get(m).toString().toUpperCase());
					
					if (material == null) {
						throw new IllegalArgumentException(materials.get(m).toString().toUpperCase() + " is not a valid material.");
					}
					
					colorIndex.put(material, i);
				}
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	public void updateMap() {
		plugin.getServer().broadcastMessage("Updating map, this may be laggy.");
		plugin.getConfig().set("last-render-update", System.currentTimeMillis());
		plugin.saveConfig();

		ArrayList<Location> current = editedChunks;

		try {
			JSONArray cache = getChunkCache();
			
			if(cache != null && !cache.isEmpty()) {
				for (Object location : cache) {
					String[] loc = location.toString().split(",");
	
					current.add(new Location(plugin.getServer().getWorld(loc[0]), Integer.parseInt(loc[1]), 0, Integer.parseInt(loc[2])));
				}
	
				writeChunkCache(new JSONArray());
			}

			for (Location loc : current) {
				JSONArray data = new JSONArray();
				data.add(Arrays.toString(generateMap(loc.getWorld(), loc.getBlockX(), loc.getBlockZ(), false, WIDTH, HEIGHT)));
				plugin.sendDataToWebserver("name=" + loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockZ() + "&data=" + data.toJSONString(), plugin.getConfig().getString("image-api-url"));
			}
		} catch (IOException | ParseException e) {
			e.printStackTrace();
		}
	}

	@SuppressWarnings("unchecked")
	public void saveEditedChunks() {
		ArrayList<Location> copy = editedChunks;
		plugin.getConfig().set("last-memory-clean", System.currentTimeMillis());
		plugin.saveConfig();

		try {
			JSONArray cache = getChunkCache();

			for (Location location : copy) {
				String loc = location.getWorld().getUID() + "," + location.getBlockX() + "," + location.getBlockZ();
				if (cache.contains(loc))
					return;
				cache.add(loc);
			}

			writeChunkCache(cache);
		} catch (ParseException | IOException e) {
			e.printStackTrace();
		}

		editedChunks.removeAll(copy);
	}

	public void registerChunk(Block b) {
		int x = (int) Math.floor(b.getX() / 128.0D) * 128;
		int z = (int) Math.floor(b.getZ() / 128.0D) * 128;
		Location loc = new Location(b.getWorld(), x, 0, z);
		if (!editedChunks.contains(loc)) {
			editedChunks.add(loc);
		}
	}

	private JSONArray getChunkCache() throws FileNotFoundException, IOException, ParseException {
		JSONParser parser = new JSONParser();

		Object file = parser.parse(new FileReader(plugin.getDataFolder() + "/chunkCache.json"));

		return file == null ? null : (JSONArray) file;
	}

	private void writeChunkCache(JSONArray data) throws IOException {
		FileWriter cacheFile = new FileWriter(plugin.getDataFolder() + "/chunkCache.json");
		cacheFile.write(data.toJSONString());
		cacheFile.close();
	}

	private int[] generateMap(World w, int xCenter, int zCenter, boolean useAsCenter, int width, int height) {
		int[] img = new int[width * height];

		HashMap<Chunk, Boolean> chunkStates = new HashMap<Chunk, Boolean>();

		int minX = 0;
		int minZ = 0;

		if (useAsCenter) {
			minX = xCenter - width / 2;
			minZ = zCenter - height / 2;
		} else {
			minX = (int) (Math.floor(xCenter / width) * width);
			minZ = (int) (Math.floor(zCenter / height) * height);
		}

		for (int z = 0; z < height; z++) {
			for (int x = 0; x < width; x++) {
				Block b = getHighestSolidAt(w, x + minX, z + minZ);
				chunkStates.putIfAbsent(b.getChunk(), Boolean.valueOf(b.getChunk().isLoaded()));

				try {
					if(colorIndex.get(b.getType()) == 12) { //Water, color is depth dependant
						if(getHighestSolidAt(w, x + minX, z + minZ - 1).getType() == Material.WATER) {
							if(b.getY() - getLowestWaterBlock(w, x + minX, z + minZ).getY() > 3) { //Darker color (1st variant)
								img[x + z * height] = colorIndex.get(b.getType()) * 4;
							} else if(b.getY() - getLowestWaterBlock(w, x + minX, z + minZ).getY()  <= 3 && b.getY() - getLowestWaterBlock(w, x + minX, z + minZ).getY() > 1) { //Normal color (2nd variant)
								img[x + z * height] = colorIndex.get(b.getType()) * 4 + 1;
							} else if(b.getY() - getLowestWaterBlock(w, x + minX, z + minZ).getY() <= 1) { //Ligher color (3rd variant aka base color)
								img[x + z * height] = colorIndex.get(b.getType()) * 4 + 2;
							}		
						} else {
							img[x + z * height] = colorIndex.get(b.getType()) * 4;
						}
					} else { //Other blocks, color depends of the Y value of the block north of it.
						if(getHighestSolidAt(w, x + minX, z + minZ - 1).getY() > b.getY()) { //Darker color (1st variant)
							img[x + z * height] = colorIndex.get(b.getType()) * 4;
						} else if(getHighestSolidAt(w, x + minX, z + minZ - 1).getY() == b.getY()) { //Normal color (2nd variant)
							img[x + z * height] = colorIndex.get(b.getType()) * 4 + 1;
						} else if(getHighestSolidAt(w, x + minX, z + minZ - 1).getY() < b.getY()) { //Ligher color (3rd variant aka base color)
							img[x + z * height] = colorIndex.get(b.getType()) * 4 + 2;
						}	
					}
				} catch(NullPointerException e) {
					plugin.getLogger().warning("Unknown Material: " + b.getType() + "\n");
					e.printStackTrace();
				}		
			}
		}

		for (Map.Entry<Chunk, Boolean> chunkState : chunkStates.entrySet()) {
			if (!chunkState.getValue()) {
				chunkState.getKey().unload();
			}
		}

		return img;
	}

	private Block getHighestSolidAt(World w, int x, int z) {
		int y = w.getHighestBlockYAt(x, z);
		while(colorIndex.get(w.getBlockAt(x, y, z).getType()) == 0) { //Skip transparent blocks
			y--;
		}
		
		return w.getBlockAt(x, y, z);
	}
	
	private Block getLowestWaterBlock(World w, int x, int z) {
		int y = getHighestSolidAt(w, x, z).getY();
		
		while(colorIndex.get(w.getBlockAt(x, y, z).getType()) == 12) { //Water blocks
			y--;
		}
		
		return w.getBlockAt(x, y + 1, z);
	}
}
