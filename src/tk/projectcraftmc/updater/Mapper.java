package tk.projectcraftmc.updater;

import java.awt.Color;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

@SuppressWarnings("unchecked")
public class Mapper {
	
	private UpdaterMain plugin;
	private Runnable mapper;
	
	private ArrayList<Color> colorIndex;
	private HashMap<Material, Integer> materialIndex;

	public Mapper(UpdaterMain instance) {
		plugin = instance;
		
		try {
			loadColors();
		} catch (Exception e) {
			plugin.getLogger().severe("An error occured whilst loading map color palette.");
			e.printStackTrace();
		}
		
		this.mapper = new Runnable() {
			
			@Override
			public void run() {
				if(plugin.updating) return;
				try {
					updateMap();
				} catch(Exception e) {
					plugin.getLogger().severe("An error occured whilst updating the map.");
					e.printStackTrace();
				}
			}
		};
		
		long delaytime = plugin.getConfig().getInt("render-update-time") * 20L;
		plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, mapper, 200, delaytime);
	};
	
	private void loadColors() throws IOException, ParseException {
		FileReader fileReader = new FileReader(plugin.getDataFolder() + "/BlockMapColors.json");
		
		StringBuilder content = new StringBuilder();
		
		int c;
		while((c = fileReader.read()) != -1) {
			content.append((char) c);
		}
			
		fileReader.close();
			
		colorIndex = new ArrayList<Color>();
		materialIndex = new HashMap<Material, Integer>();

		JSONParser parser = new JSONParser();
		Object object = parser.parse(content.toString());
		JSONArray ids = (JSONArray) object;

		for (int i = 0; i < ids.size(); i++) {
			JSONObject id = (JSONObject) ids.get(i);
			JSONArray materials = (JSONArray) id.get("materials");
			JSONObject colorObj = (JSONObject) id.get("color");

			for (int m = 0; m < materials.size(); m++) {
				Material material = Material.getMaterial(materials.get(m).toString().toUpperCase());
				
				if (material == null) {
					throw new IllegalArgumentException(materials.get(m).toString().toUpperCase() + " is not a valid material.");
				}
					
				materialIndex.put(material, i);				
			}
			
			Color color = new Color(
					Integer.parseInt(colorObj.get("r").toString()), 
	                Integer.parseInt(colorObj.get("g").toString()), 
	                Integer.parseInt(colorObj.get("b").toString()), 
	                Integer.parseInt(colorObj.get("a").toString()));
			
			colorIndex.add(new Color(
					Math.floorDiv(color.getRed() * 180, 255),
					Math.floorDiv(color.getGreen() * 180, 255),
					Math.floorDiv(color.getBlue() * 180, 255),
					color.getAlpha()));
			
			colorIndex.add(new Color(
					Math.floorDiv(color.getRed() * 220, 255),
					Math.floorDiv(color.getGreen() * 220, 255),
					Math.floorDiv(color.getBlue() * 220, 255),
					color.getAlpha()));
			
			colorIndex.add(color);
			
			colorIndex.add(new Color(
					Math.floorDiv(color.getRed() * 135, 255),
					Math.floorDiv(color.getGreen() * 135, 255),
					Math.floorDiv(color.getBlue() * 135, 255),
					color.getAlpha()));
		}
	}
	
	public void updateMap() throws IOException, ParseException {
		plugin.getServer().broadcastMessage("Updating map, this may be laggy.");
		
		plugin.updating = true;
		
		JSONParser parser = new JSONParser();
		
		ArrayList<SuperChunk> current = plugin.watchdog.getEditedChunks();
		JSONObject apidata = (JSONObject) parser.parse(plugin.getDataFromWebserver(plugin.getConfig().getString("api-fetch-url")));
		JSONArray minimaps = (JSONArray) apidata.get("miniMapList");
		
		int totalWorkload = apidata.size() + current.size();
		double done = 0.0;
		
		if(!current.isEmpty()) {
	    	for (SuperChunk c : current) {
				JSONArray data = compressMap(generateMap(c.getWorld(), c.getX(), c.getZ(), plugin.CHUNKSIZE), plugin.COMPRESSION, plugin.CHUNKSIZE);	    		
				
				JSONObject metaData = new JSONObject();
				metaData.put("x", c.getX());
				metaData.put("z", c.getZ());
				metaData.put("size", plugin.CHUNKSIZE);
				metaData.put("world", c.getWorld().getEnvironment().toString().toLowerCase());
				metaData.put("isMiniMap", false);
				
				JSONObject complete = new JSONObject();
				complete.put("data", data);
				complete.put("metaData", metaData);
				
				plugin.sendDataToWebserver(complete.toJSONString(), plugin.getConfig().getString("api-upload-url"));
				done++;
				plugin.getLogger().info("Updating: " + done / totalWorkload * 100 + "%"); 
			}
		}
		
		plugin.watchdog.clearChunkCache();
		
		for(int m = 0; m < minimaps.size(); m++) {
			JSONObject minimapObj = (JSONObject) minimaps.get(m);
			int sideLength = Integer.parseInt(minimapObj.get("size").toString());
			int parts = (int) Math.ceil(1.0d * sideLength / plugin.CHUNKSIZE);
			int x = Integer.parseInt(minimapObj.get("x").toString());
			int z = Integer.parseInt(minimapObj.get("z").toString());
			System.out.println("Size: "  + sideLength + " parts: " + parts);
			JSONArray data = new JSONArray();

			for(int zpart = 0; zpart < parts; zpart++) {
				for(int xpart = 0; xpart < parts; xpart++) {
					JSONArray map = generateMap(plugin.getServer().getWorlds().get(0), x + xpart * plugin.CHUNKSIZE, z + zpart * plugin.CHUNKSIZE, plugin.CHUNKSIZE);
					data.addAll(map);
				}
			}
			
			JSONObject metaData = new JSONObject();
			metaData.put("x", x);
			metaData.put("z", z);
			metaData.put("size", sideLength);
			metaData.put("world", "normal");
			metaData.put("isMiniMap", true);
			
			JSONObject complete = new JSONObject();
			complete.put("data", data);
			complete.put("metaData", metaData);
			
			plugin.sendDataToWebserver(complete.toJSONString(), plugin.getConfig().getString("api-upload-url"));
			done++;
			plugin.getLogger().info("Updating: " + Math.round((done / totalWorkload) * 100) + "%");
		}
		
		plugin.getServer().broadcastMessage("Map updated.");
		plugin.updating = false;
		System.gc();
	}
	
	private JSONArray generateMap(World w, int startX, int startZ, int size) {
		JSONArray img = new JSONArray();
		
		int minX = Math.floorDiv(startX, size) * size;
		int minZ = Math.floorDiv(startZ, size) * size;
		
		ArrayList<ChunkSnapshot> cache = new ArrayList<ChunkSnapshot>();
		
		for(int nz = 0; nz < size / 16; nz++) { //TODO: Fix
			for(int nx = 0; nx < (size + 16) / 16; nx++) {
				int zOffset = Math.floorDiv(minZ + nx * 16, 16) * 16;
				int xOffset = Math.floorDiv(minX - size / 16 + nz * 16, 16) * 16;
				cache.add(w.getChunkAt(xOffset, zOffset).getChunkSnapshot());
			}
		}
		
		for(int c = 0; c < size * size / (16 * 16); c++) {
			ChunkSnapshot chunk = null;
			ChunkSnapshot north = null;
			
			if(cache.size() > size / 16) {
				chunk = cache.get(size / 16);
			} else {
				chunk = cache.get(0);
			}
			
			for(int rz = 0; rz < 16; rz++) {
				if(rz == 0) {
					north = cache.get(0);
				} else if (rz == 1) {
					north = chunk;
					cache.remove(0);
				}
				
				for(int rx = 0; rx < 16; rx++) {
					int y = getHighestSolidAt(chunk, rx, rz);
					Material m = chunk.getBlockType(rx, y, rz);
					int northY = getHighestSolidAt(north, rx, 15);
					
					Color mColor = getBlockColor(materialIndex.get(m), y - northY);
					
					img.add(mColor.getRed());				
					img.add(mColor.getGreen());
					img.add(mColor.getBlue());
				}
			}
			System.out.println("Chunk mapped");
		}
		
		return img;
	}

	private Color getBlockColor(int mIndex, int dY) {
        if (mIndex == 12) { 										
			if (dY > 3) 			return 	colorIndex.get(mIndex * 4); 			
			if (dY > 1 && dY <= 3)	return 	colorIndex.get(mIndex * 4 + 1);
									return 	colorIndex.get(mIndex * 4 + 2);
		}
        
		if (dY > 0) 	return colorIndex.get(mIndex * 4);
		if (dY == 0) 	return colorIndex.get(mIndex * 4 + 1);
						return colorIndex.get(mIndex * 4 + 2);
	}
	
	private JSONArray compressMap(JSONArray img, int compression, int side) {
        JSONArray newImg = new JSONArray();
        int newPixelCount = (img.size() / 3) / (compression * compression);
        int newWidth = (int) Math.floor(Math.sqrt(newPixelCount));

        for(int pixel = 0; pixel < newPixelCount; pixel++) {
            int red = 0;
            int green = 0;
            int blue = 0;
            
            for (int rz = 0; rz < compression; rz++) {
                for (int rx = 0; rx < compression; rx++) {
                    int curPixelY = Math.floorDiv(pixel, newWidth);
                    int curPixelX = pixel - curPixelY * newWidth;
                    int yOffset   = curPixelY * compression * side;
                    int curIndex  = 3 * (rx + rz * side + curPixelX * compression + yOffset);

                    red   += Integer.parseInt(img.get(curIndex).toString());
                    green += Integer.parseInt(img.get(curIndex + 1).toString());
                    blue  += Integer.parseInt(img.get(curIndex + 2).toString());
                }
            }
            
            newImg.add(Math.floorDiv(red, compression * compression));
            newImg.add(Math.floorDiv(green, compression * compression));
            newImg.add(Math.floorDiv(blue, compression * compression));
        }
        
        return newImg;
    }
	
	private int getHighestSolidAt(ChunkSnapshot chunk, int x, int z) {
		int y = chunk.getHighestBlockYAt(x, z);
		
		while(materialIndex.get(chunk.getBlockType(x, y, z)) == 0 || materialIndex.get(chunk.getBlockType(x, y, z)) == 12) { //Skip transparent blocks
			y--;
		}
		
		return y;
	}
}
