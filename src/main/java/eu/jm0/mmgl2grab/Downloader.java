package eu.jm0.mmgl2grab;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Collection;
import java.util.LinkedList;
import java.util.zip.GZIPInputStream;

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
 * This class pipes a remote URL to a local file
 * 
 * @author Josua Mayer
 */

public class Downloader implements Runnable {
	protected final int SEGMENT_SIZE = 2048 * 1000;
	protected File destination;
	protected URL url;
	protected boolean decompress;
	protected volatile boolean done;
	protected volatile boolean failed;
	protected volatile Collection<Exception> failure;
	protected FileOutputStream os;
	protected FileChannel oc;
	protected ReadableByteChannel ic;

	public Downloader(File destination, URL url, boolean decompress) {
		this.destination = destination;
		this.url = url;
		this.decompress = decompress;
		this.done = false;
		this.failure = new LinkedList<Exception>();
		this.os = null;
		this.oc = null;
		this.ic = null;
	}

	protected void prepare() {
		// set up source and destination
		try {
			// open destination file
			os = new FileOutputStream(destination);
			oc = os.getChannel();
		} catch (FileNotFoundException | SecurityException e) {
			oc = null;
			failure.add(e);
			synchronized (failure) {
				failed = true;
			}
			return;
		}
		try {
			// open origin url
			if(decompress)
				ic = Channels.newChannel(new GZIPInputStream(url.openStream()));
			else
				ic = Channels.newChannel(url.openStream());
		} catch (IOException e) {
			ic = null;
			failure.add(e);
			synchronized (failure) {
				failed = true;
			}
			return;
		}
	}

	protected void copy() {
		if (failed)
			return;

		ByteBuffer buffer = ByteBuffer.allocate(SEGMENT_SIZE);
		while (true) {
			synchronized (failure) {
				if (failed)
					return;
			}

			try {
				int r = ic.read(buffer);
				buffer.flip();
				oc.write(buffer);
				buffer.clear();
				// TODO: what if fewer bytes were written than read?

				if (r == -1)
					return;
			} catch (IOException e) {
				synchronized (failure) {
					failed = true;
					failure.add(e);
				}
				return;
			}
		}
	}

	protected void cleanup() {
		try {
			if (failed && oc != null)
				oc.truncate(0);
		} catch (IOException e) {
			// save the failure, but don't mark as failed
			failure.add(e);
		}

		try {
			if (failed)
				destination.delete();
		} catch (SecurityException e) {
			// save the failure, but don't mark as failed
			failure.add(e);
		}

		try {
			if (ic != null)
				ic.close();
		} catch (IOException e) {
			// save the failure, but don't mark as failed
			failure.add(e);
		}
		try {
			if (oc != null)
				oc.close();
		} catch (IOException e) {
			// save the failure, but don't mark as failed
			failure.add(e);
		}
		try {
			if (os != null) {
				os.close();
			}
		} catch (IOException e) {
			// save the failure, but don't mark as failed
			failure.add(e);
		}
	}

	@Override
	public void run() {
		prepare();
		copy();
		cleanup();
		synchronized (failure) {
			done = true;
		}
	}

	public void stop() {
		synchronized (failure) {
			if (!done) {
				failed = true;
				failure.add(new InterruptedException());
			}
		}
	}

	public boolean hasFinished() {
		synchronized (failure) {
			return done;
		}
	}

	public boolean hasFailed() {
		synchronized (failure) {
			return failed;
		}
	}

	public Collection<Exception> getFailure() {
		synchronized (failure) {
			if (done)
				return failure;
			else
				return null;
		}
	}
}
