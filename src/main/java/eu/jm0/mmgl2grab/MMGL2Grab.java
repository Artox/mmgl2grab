package eu.jm0.mmgl2grab;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.LinkedList;
import java.util.logging.Level;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * MIT License
 * 
 * Copyright (c) 2016 Josua Mayer <josua.mayer97@gmail.com>
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * 
 * 
 * This class manages a copy of the MaxMind GeoLite2 IP Database.
 * 
 * @author Josua Mayer
 */
public class MMGL2Grab extends JavaPlugin implements MMGL2GrabService {
	protected volatile File DB = null;
	protected final String DBname = "geolite2.mmdb";
	protected final String DBurlstr = "http://geolite.maxmind.com/download/geoip/database/GeoLite2-Country.mmdb.gz";
	protected volatile boolean ready = false;
	protected Downloader download = null;
	protected BukkitTask downloadTask = null;

	/**
	 * This is the first call. make sure data folder exists
	 */
	@Override
	public void onLoad() {
		File dir = getDataFolder();
		if (!dir.exists()) {
			try {
				dir.mkdir();
			} catch (SecurityException e) {
				getLogger().log(Level.SEVERE, "Failed to create data directory", e);
				return;
			}
		}
		if (!dir.canRead()) {
			getLogger().log(Level.SEVERE, "Plugin data directory is not readable");
			return;
		}
		if (!dir.isDirectory()) {
			getLogger().log(Level.SEVERE, "Plugin data directory is not a directory");
			return;
		}
	}

	/**
	 * Called when the plugin gets enabled. Registers public API as a service,
	 * checks if the database exists and triggers a download if missing
	 */
	@Override
	public void onEnable() {
		// register service
		getServer().getServicesManager().register(MMGL2GrabService.class, this, this, ServicePriority.Normal);

		File dir = getDataFolder();
		if (!dir.exists() || !dir.canRead() || !dir.isDirectory()) {
			// these errors have been reported in load(), return quietly
			return;
		}

		File db = new File(getDataFolder(), DBname);
		if (!db.exists()) {
			getLogger().log(Level.INFO, "GeoIP database not found");
			download();
		}

		if (!db.isFile()) {
			getLogger().log(Level.SEVERE, "GeoIP database is not a file");
			return;
		}

		if (!db.canRead()) {
			getLogger().log(Level.SEVERE, "GeoIP database is not readable");
			return;
		}

		synchronized (this) {
			DB = db;
			ready = true;
			notifyAll();
		}
	}

	/**
	 * Called when the plugin gets disabled, e.g. on server shutdown. cancels
	 * download task if any
	 */
	@Override
	public void onDisable() {
		if (download != null) {
			download.stop();

			// at most 1 second
			final long timeout = 1 * 1000;
			final long start = System.currentTimeMillis();
			while (!download.hasFinished() && System.currentTimeMillis() < start + timeout) {
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {
					break;
				}
			}
			if (!download.hasFinished()) {
				getLogger().log(Level.WARNING, "GeoIP database download task hasn't terminated in time");
			}
			for (Exception e : download.getFailure()) {
				getLogger().log(Level.SEVERE, "EXCEPTION", e);
			}
		}

		synchronized (this) {
			for (InputStream is : handles) {
				try {
					is.close();
				} catch (IOException e) {
				}
			}
			handles.clear();

			DB = null;
			ready = false;
			notifyAll();
		}

		// deregister service
		getServer().getServicesManager().unregister(MMGL2GrabService.class, this);
	}

	/**
	 * downloads new / initial geoip database file
	 */
	protected void download() {
		// create file if it doesn't exist
		File db = new File(getDataFolder(), DBname);
		try {
			db.createNewFile();
		} catch (SecurityException | IOException e) {
			getLogger().log(Level.SEVERE, "Failed to create GeoIP database file", e);
			return;
		}

		// open for writing
		if (!db.canWrite()) {
			getLogger().log(Level.SEVERE, "GeoIP database file is not writable");
			return;
		}

		// create downloader task
		try {
			download = new Downloader(db, new URL(DBurlstr), true);
			downloadTask = getServer().getScheduler().runTaskAsynchronously(this, download);
		} catch (MalformedURLException e) {
			getLogger().log(Level.SEVERE, "Failed to create GeoIP database download task", e);
			return;
		}
	}

	// API
	protected volatile Collection<InputStream> handles = new LinkedList<InputStream>();

	@Override
	public synchronized boolean isReady() {
		return ready;
	}

	@Override
	public synchronized void waitTillReady(long timeout) {
		long start = System.currentTimeMillis();
		while (!ready && start < System.currentTimeMillis()) {
			try {
				wait(timeout);
			} catch (InterruptedException e) {
			}
		}
	}

	@Override
	public synchronized InputStream openCountryDatabase() {
		if (!ready)
			return null;

		try {
			InputStream is = new FileInputStream(DB);
			handles.add(is);
			return is;
		} catch (FileNotFoundException e) {
			return null;
		}
	}

	@Override
	public void closeCountryDatabase(InputStream is) {
		if (handles.remove(is)) {
			try {
				is.close();
			} catch (IOException e) {
			}
		}
	}
}
