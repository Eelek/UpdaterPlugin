package tk.projectcraftmc.updater;

import java.awt.Color;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
				try {
					updateMap();
				} catch(Exception e) {
					plugin.getLogger().severe("An error occured whilst updating the map.");
					e.printStackTrace();
				}
			}
		};
		
		long delaytime = plugin.getConfig().getInt("render-update-time") * 20L;
		plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, mapper, delaytime, delaytime);
	};
	
	private void loadColors() throws IOException, ParseException {
		URL fileURL = getClass().getResource("/BlockMapColors.json");
		File jsonFile = new File(fileURL.getFile());
		FileReader fileReader = new FileReader(jsonFile);
		
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
			
			Color color = new Color(Integer.parseInt(colorObj.get("r").toString()), 
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
		
		ArrayList<LightChunk> current = plugin.watchdog.getEditedChunks();
		
		JSONParser parser = new JSONParser();
		
		JSONArray minimaps = (JSONArray) parser.parse(plugin.getDataFromWebserver(plugin.getConfig().getString("api-fetch-url")));
		
		if(!current.isEmpty()) {
	    	for (LightChunk c : current) {
				//TODO: Rewrite generation
			}
		}
		
		plugin.watchdog.clearChunkCache();
		
		//TODO: Update minimaps
		
		plugin.getServer().broadcastMessage("Map updated.");
		System.gc();
	}
	
	private JSONArray generateMap(World w, int xCenter, int zCenter, boolean useAsCenter, int width, int height) {
		JSONArray img = new JSONArray();

		int minX = Math.floorDiv(xCenter, width) * width;
		int minZ = Math.floorDiv(zCenter, height) * height;

		for (int z = minZ; z < height; z++) {
			for (int x = minX; x < width; x++) {
				Block b = getHighestSolidAt(w, x, z);
				JSONArray pixel = new JSONArray();

				try {
					if(materialIndex.get(b.getType()) == 12) { //Water, color is depth dependant
						if(getHighestSolidAt(w, x, z - 1).getType() == Material.WATER) {
							if(b.getY() - getLowestWaterBlock(w, x, z).getY() > 3) { //Darker color (1st variant)
								Color mColor = getMaterialColor(b.getType(), 0);
								pixel.add(mColor.getRed());
								pixel.add(mColor.getGreen());
								pixel.add(mColor.getBlue());
							} else if(b.getY() - getLowestWaterBlock(w, x, z).getY()  <= 3 && b.getY() - getLowestWaterBlock(w, x + minX, z + minZ).getY() > 1) { //Normal color (2nd variant)
								Color mColor = getMaterialColor(b.getType(), 1);
								pixel.add(mColor.getRed());
								pixel.add(mColor.getGreen());
								pixel.add(mColor.getBlue());
							} else if(b.getY() - getLowestWaterBlock(w, x, z).getY() <= 1) { //Ligher color (3rd variant aka base color)
								Color mColor = getMaterialColor(b.getType(), 2);
								pixel.add(mColor.getRed());
								pixel.add(mColor.getGreen());
								pixel.add(mColor.getBlue());
							}		
						} else {
							Color mColor = getMaterialColor(b.getType(), 0);
							pixel.add(mColor.getRed());
							pixel.add(mColor.getGreen());
							pixel.add(mColor.getBlue());
						}
					} else { //Other blocks, color depends of the Y value of the block north of it.
						if(getHighestSolidAt(w, x, z - 1).getY() > b.getY()) { //Darker color (1st variant)
							Color mColor = getMaterialColor(b.getType(), 0);
							pixel.add(mColor.getRed());
							pixel.add(mColor.getGreen());
							pixel.add(mColor.getBlue());
						} else if(getHighestSolidAt(w, x, z - 1).getY() == b.getY()) { //Normal color (2nd variant)
							Color mColor = getMaterialColor(b.getType(), 1);
							pixel.add(mColor.getRed());
							pixel.add(mColor.getGreen());
							pixel.add(mColor.getBlue());
						} else if(getHighestSolidAt(w, x, z - 1).getY() < b.getY()) { //Ligher color (3rd variant aka base color)
							Color mColor = getMaterialColor(b.getType(), 2);
							pixel.add(mColor.getRed());
							pixel.add(mColor.getGreen());
							pixel.add(mColor.getBlue());
						}	
					}
					
					img.add(pixel);
					pixel = null;
				} catch(NullPointerException e) {
					plugin.getLogger().warning("Unknown Material: " + b.getType() + "\n");
					e.printStackTrace();
				}		
			}
		}

		return img;
	}
	
	/*
	public void generateArea(World w, int startX, int startZ, int endX, int endZ) {
		if(startX > endX) {
			int tmp = startX;
			startX = endX;
			endX = tmp;
		}
		
		if(startZ > endZ) {
			int tmp = startZ;
			startZ = endZ;
			endZ = tmp;
		}
		
		startX = (int) Math.floor(startX / (double) WIDTH) * WIDTH;
		startZ = (int) Math.floor(startZ / (double) HEIGHT) * HEIGHT;
		endX = (int) Math.ceil(endX / (double) WIDTH) * WIDTH;
		endZ = (int) Math.ceil(endZ / (double) HEIGHT) * HEIGHT;
		
		for(int z = startZ; z < endZ; z += 16) {
			for(int x = startX; x < endX; x += 16) {
				plugin.watchdog.registerChunk(w.getBlockAt(x, 0, z)); //Make sure all chunks in area will be exported
			}
		}
		
		try {
			updateMap();
		} catch (IOException | ParseException e) {
			e.printStackTrace();
		}
	}
	*/

	private Block getHighestSolidAt(World w, int x, int z) {
		int y = w.getHighestBlockYAt(x, z);
		while(materialIndex.get(w.getBlockAt(x, y, z).getType()) == 0) { //Skip transparent blocks
			y--;
		}
		
		return w.getBlockAt(x, y, z);
	}
	
	private Block getLowestWaterBlock(World w, int x, int z) {
		int y = getHighestSolidAt(w, x, z).getY();
		
		while(materialIndex.get(w.getBlockAt(x, y, z).getType()) == 12) { //Water blocks
			y--;
		}
		
		return w.getBlockAt(x, y + 1, z);
	}
	
	private Color getMaterialColor(Material m, int offset) {
		return colorIndex.get(materialIndex.get(m) * 4 + offset);
	}
}
