/* Copyright (c) 2012 Jesper Öqvist <jesper@llbit.se>
 *
 * This file is part of Chunky.
 *
 * Chunky is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Chunky is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with Chunky.  If not, see <http://www.gnu.org/licenses/>.
 */
package se.llbit.chunky.renderer;

import java.util.Random;

import se.llbit.chunky.renderer.scene.Camera;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.log.Log;
import se.llbit.math.QuickMath;
import se.llbit.math.Ray;

/**
 * Performs rendering work.
 * @author Jesper Öqvist <jesper@llbit.se>
 */
public class RenderWorker extends Thread {

	/**
	 * Sleep interval (in ns)
	 */
	private static final int SLEEP_INTERVAL = 75000000;

	private final int id;
	private final AbstractRenderManager manager;

	private final WorkerState state;
	private long jobTime = 0;

	/**
	 * Create a new render worker, slave to a given render manager.
	 * @param manager
	 * @param id
	 * @param seed
	 */
	public RenderWorker(AbstractRenderManager manager, int id, long seed) {
		super("3D Render Worker " + id);

		this.manager = manager;
		this.id = id;
		state = new WorkerState();
		state.random = new Random(seed);
		state.ray = new Ray();
	}

	@Override
	public void run() {
		try {
			try {
				while (!isInterrupted()) {
					work(manager.getNextJob());
					manager.jobDone();
				}
			} catch (InterruptedException e) {
			}
		} catch (Throwable e) {
			Log.error("Render worker " + id +
					" crashed with uncaught exception.", e);
		}
	}

	/**
	 * Perform work
	 * @param jobId
	 * @throws InterruptedException interrupted while sleeping
	 */
	private final void work(int jobId) throws InterruptedException {

		Scene scene = manager.bufferedScene();

		Random random = state.random;
		Ray ray = state.ray;

		int width = scene.canvasWidth();
		int height = scene.canvasHeight();

		double halfWidth = width/(2.0*height);
		double invHeight = 1.0 / height;

		// calculate pixel bounds for this job
		int xjobs = (width+(manager.tileWidth-1))/manager.tileWidth;
		int x0 = manager.tileWidth * (jobId % xjobs);
		int x1 = Math.min(x0 + manager.tileWidth, width);
		int y0 = manager.tileWidth * (jobId / xjobs);
		int y1 = Math.min(y0 + manager.tileWidth, height);

		double[] samples = scene.getSampleBuffer();
		final Camera cam = scene.camera();

		long jobStart = System.nanoTime();

		if (scene.getRenderState() != RenderState.PREVIEW) {

			// this is intentionally incorrectly indented for readability
			for (int y = y0; y < y1; ++y) {
				int offset = y * width * 3 + x0 * 3;
				for (int x = x0; x < x1; ++x) {

					double sr = 0;
					double sg = 0;
					double sb = 0;

					for (int i = 0; i < RenderConstants.SPP_PER_PASS; ++i) {
						double oy = random.nextDouble();
						double ox = random.nextDouble();

						cam.calcViewRay(ray, random,
								(-halfWidth + (x + ox) * invHeight),
								(-.5 + (y + oy) * invHeight));

						scene.pathTrace(state);

						sr += ray.color.x;
						sg += ray.color.y;
						sb += ray.color.z;
					}
					double sinv = 1.0 / (scene.spp + RenderConstants.SPP_PER_PASS);
					samples[offset+0] = (samples[offset+0] * scene.spp + sr) * sinv;
					samples[offset+1] = (samples[offset+1] * scene.spp + sg) * sinv;
					samples[offset+2] = (samples[offset+2] * scene.spp + sb) * sinv;

					if (scene.shouldFinalizeBuffer()) {
						scene.finalizePixel(x, y);
					}

					offset += 3;
				}
			}

		} else {

			Ray target = new Ray(ray);
			boolean hit = scene.trace(target);
			int tx = (int) QuickMath.floor(target.o.x + target.d.x * Ray.OFFSET);
			int ty = (int) QuickMath.floor(target.o.y + target.d.y * Ray.OFFSET);
			int tz = (int) QuickMath.floor(target.o.z + target.d.z * Ray.OFFSET);

			// This is intentionally incorrectly indented for readability.
			for (int x = x0; x < x1; ++x)
			for (int y = y0; y < y1; ++y) {

			boolean firstFrame = scene.previewCount > 1;
			if (firstFrame) {
				if (((x+y)%2) == 0) {
					continue;
				}
			} else {
				if (((x+y)%2) != 0) {
					scene.finalizePixel(x, y);
					continue;
				}
			}

			// Draw the crosshair.
			if (x == width / 2 && (y >= height / 2 - 5 && y <= height / 2 + 5) ||
					y == height / 2 && (x >= width / 2 - 5 && x <= width / 2 + 5)) {
				samples[(y*width+x)*3+0] = 0xFF;
				samples[(y*width+x)*3+1] = 0xFF;
				samples[(y*width+x)*3+2] = 0xFF;
				scene.finalizePixel(x, y);
				continue;
			}

			cam.calcViewRay(ray, random,
					(-halfWidth + (double)x * invHeight),
					(-.5 + (double)y * invHeight));

			scene.quickTrace(state);

			// do target highlighting
			int rx = (int) QuickMath.floor(ray.o.x + ray.d.x * Ray.OFFSET);
			int ry = (int) QuickMath.floor(ray.o.y + ray.d.y * Ray.OFFSET);
			int rz = (int) QuickMath.floor(ray.o.z + ray.d.z * Ray.OFFSET);
			if (hit && tx == rx && ty == ry && tz == rz) {
				/*ray.color.x = ray.color.x * 0.5 + 0.5;
				ray.color.y = ray.color.y * 0.5;
				ray.color.z = ray.color.z * 0.5;
				ray.color.w = ray.color.w * 0.5 + 0.5;*/
				ray.color.x = 1 - ray.color.x;
				ray.color.y = 1 - ray.color.y;
				ray.color.z = 1 - ray.color.z;
				ray.color.w = 1;
			}

			samples[(y*width+x)*3+0] = ray.color.x;
			samples[(y*width+x)*3+1] = ray.color.y;
			samples[(y*width+x)*3+2] = ray.color.z;

			scene.finalizePixel(x, y);

			if (firstFrame) {
				if (y%2 == 0 && x < (width-1)) {
					// copy forward
					scene.copyPixel(y*width + x, 1);
				} else if (y%2 != 0 && x > 0) {
					// copy backward
					scene.copyPixel(y*width + x, -1);
				}
			}

			}
		}
		jobTime += System.nanoTime() - jobStart;
		if (jobTime > SLEEP_INTERVAL) {
			if (manager.cpuLoad < 100) {
				// sleep = jobTime * (1-utilization) / utilization
				double load = (100.0 - manager.cpuLoad) / manager.cpuLoad;
				sleep((long) ((jobTime/1000000.0) * load));
			}
			jobTime = 0;
		}
	}

}
