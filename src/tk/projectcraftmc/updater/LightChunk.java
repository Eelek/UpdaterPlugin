package tk.projectcraftmc.updater;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.json.simple.JSONObject;

public class LightChunk {
	
	private World w;
	private int x, z;
	
	public LightChunk(World w, int x, int z) {
		this.w = w;
		this.x = x;
		this.z = z;
	}
	
	public World getWorld() {
		return w;
	}
	
	public int getX() {
		return x;
	}
	
	public int getZ() {
		return z;
	}
	
	@SuppressWarnings("unchecked")
	public JSONObject serialize() {
		JSONObject c = new JSONObject();
		c.put("world", w.getUID());
		c.put("x", x);
		c.put("z", z);
		
		return c;
	}
	
	static LightChunk deserialize(JSONObject c) {
		World w = Bukkit.getServer().getWorld(c.get("world").toString());
		int x = Integer.parseInt(c.get("x").toString());
		int z = Integer.parseInt(c.get("z").toString());
		
		return new LightChunk(w, x, z);
	}
}
