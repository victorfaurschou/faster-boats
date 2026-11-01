package com.victorfaurschou.fasterboats.gametest;

import com.mojang.blaze3d.platform.InputConstants;
import com.victorfaurschou.fasterboats.FasterBoatsConfig;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.particle.BubbleParticle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.particle.ParticleGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Drives a boat down a straight water channel and checks every README feature with numbers:
 * forward and reverse speed with the mod on and off, the straight-line boost (when it starts,
 * its top speed, its particle trail, and that turning resets it), and the {@code /faster-boats}
 * enable and version commands typed through the real chat screen.
 *
 * <p>Expected speeds follow from vanilla's water physics: a boat keeps 90% of its horizontal velocity
 * each tick, so its top speed is {@code acceleration / 0.1} blocks per tick (x20 for blocks per second).
 */
public class FasterBoatsClientGameTest implements FabricClientGameTest {
	private static final String ID = "faster-boats-gametest";
	private static final Logger LOGGER = LoggerFactory.getLogger(ID);

	// README: "1.3x/10.4 bps (vanilla: 8 bps) forwards and in reverse", boost "topping out at 1.6x/16.6 bps"
	private static final double VANILLA_FORWARD_BPS = 8.0;    // vanilla acceleration 0.04
	private static final double MOD_FORWARD_BPS = 10.4;       // 0.052
	private static final double MOD_BOOSTED_BPS = 16.64;      // 0.052 + 0.0312
	private static final double VANILLA_REVERSE_BPS = 1.0;    // vanilla acceleration 0.005
	private static final double MOD_REVERSE_BPS = 1.3;        // 0.0065
	private static final double FORWARD_TOLERANCE_BPS = 0.3;
	private static final double REVERSE_TOLERANCE_BPS = 0.1;

	// README: the boost builds after "6–8 seconds" of straight travel (120–160 ticks). The speed rises
	// measurably (> base + 0.25 bps) about 15 ticks after it engages, because the extra acceleration ramps
	// in at 0.5% per tick and water drag lags it. So the visible onset falls in about 135–175 ticks.
	private static final int BOOST_ONSET_MIN_TICKS = 130;
	private static final int BOOST_ONSET_MAX_TICKS = 180;
	private static final double BOOST_ONSET_MARGIN_BPS = 0.25;
	private static final double TRAIL_MIN_PARTICLES = 5.0;    // 6 bubbles per tick at full boost
	private static final double NO_TRAIL_MAX_PARTICLES = 0.5;

	// The track: a channel 2 deep and 13 wide, cut into the flat test world (grass at y=-61), running east (+X).
	private static final int TRACK_MIN_X = 0;
	private static final int TRACK_MAX_X = 639;
	private static final int TRACK_HALF_WIDTH = 6;
	private static final int WATER_BOTTOM_Y = -62;
	private static final int WATER_TOP_Y = -61;
	private static final double LANE_Z = 0.5;
	private static final double FORWARD_START_X = 8.5;
	private static final double REVERSE_START_X = 40.5;
	private static final float EAST_YAW = -90.0F;

	private static Field particleGroupsField;
	private static Field particleQueueField;
	private static Field chatMessagesField;

	private final List<String> failures = new ArrayList<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		try (TestSingleplayerContext singleplayer = ctx.worldBuilder().create()) {
			TestServerContext server = singleplayer.getServer();
			singleplayer.getConnection().waitForChunksRender();

			server.runCommand("gamemode creative @a");
			server.runCommand("time set noon");
			server.runCommand("weather clear");
			buildTrack(ctx, server);

			checkVersionCommand(ctx);
			checkTrue("mod is enabled by default", FasterBoatsConfig.enabled, "FasterBoatsConfig.enabled=" + FasterBoatsConfig.enabled);

			forwardWithBoost(ctx, server);
			reverse(ctx, server, "reverse, mod enabled", MOD_REVERSE_BPS);

			setEnabledThroughChat(ctx, false);
			forwardDisabled(ctx, server);
			reverse(ctx, server, "reverse, mod disabled", VANILLA_REVERSE_BPS);

			setEnabledThroughChat(ctx, true);
			forwardReenabled(ctx, server);

			server.runCommand("ride @a dismount");
		}

		if (failures.isEmpty()) {
			log("ALL CHECKS PASSED");
		} else {
			log(failures.size() + " CHECK(S) FAILED");
			throw new AssertionError("Faster Boats game test failed:\n - " + String.join("\n - ", failures));
		}
	}

	// ---- Scenarios ----

	private void forwardWithBoost(ClientGameTestContext ctx, TestServerContext server) {
		String run = "forward, mod enabled";
		boardNewBoat(ctx, server, FORWARD_START_X, EAST_YAW);
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));

		// 480 ticks straight (base speed, boost onset, full boost), then a short left-right wiggle
		// that brings the boat back to its heading, then 112 more ticks straight.
		int turnTick = 480;
		List<Sample> samples = drive(ctx, run, o -> o.keyUp, 600, tick -> {
			if (tick == 470) {
				log("screenshot " + ctx.takeScreenshot("boosted-trail"));
			}
			if (tick == turnTick) {
				ctx.getInput().holdKey(o -> o.keyLeft);
			} else if (tick == turnTick + 4) {
				ctx.getInput().releaseKey(o -> o.keyLeft);
				ctx.getInput().holdKey(o -> o.keyRight);
			} else if (tick == turnTick + 8) {
				ctx.getInput().releaseKey(o -> o.keyRight);
			}
		});
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		if (samples.size() < 600) {
			return;
		}

		logTrace(run, samples);
		checkNear(run + ": base speed (ticks 70-110)", meanSpeed(samples, 70, 110), MOD_FORWARD_BPS, FORWARD_TOLERANCE_BPS, "bps");
		checkTrue(run + ": moves east (forward)", samples.get(110).pos().x > samples.get(70).pos().x, "x " + fmt(samples.get(70).pos().x) + " -> " + fmt(samples.get(110).pos().x));

		int onset = -1;
		for (int t = 20; t < turnTick; t++) {
			if (speedAt(samples, t) > MOD_FORWARD_BPS + BOOST_ONSET_MARGIN_BPS) { // tick t is the (t+1)th tick with the key held
				onset = t;
				break;
			}
		}
		checkRange(run + ": boost starts after straight travel (first tick above " + fmt(MOD_FORWARD_BPS + BOOST_ONSET_MARGIN_BPS) + " bps)",
				onset, BOOST_ONSET_MIN_TICKS, BOOST_ONSET_MAX_TICKS, "ticks");
		checkNear(run + ": boosted top speed (ticks 430-470)", meanSpeed(samples, 430, 470), MOD_BOOSTED_BPS, FORWARD_TOLERANCE_BPS, "bps");

		checkAtMost(run + ": no bubble trail before the boost (mean bubble particles, ticks 70-110)", meanParticles(samples, 70, 110), NO_TRAIL_MAX_PARTICLES);
		checkAtLeast(run + ": bubble trail while boosted (mean bubble particles, ticks 430-470)", meanParticles(samples, 430, 470), TRAIL_MIN_PARTICLES);

		float maxYawOffset = 0.0F;
		for (int t = turnTick; t < samples.size(); t++) {
			maxYawOffset = Math.max(maxYawOffset, Math.abs(samples.get(t).yaw() - EAST_YAW));
		}
		checkAtLeast(run + ": the wiggle turned the boat (max yaw offset, degrees)", maxYawOffset, 5.0);
		checkAtMost(run + ": heading restored after the wiggle (yaw offset at tick 599, degrees)", Math.abs(samples.get(599).yaw() - EAST_YAW), 3.0);
		checkNear(run + ": turning resets the boost (speed ticks 548-588)", meanSpeed(samples, 548, 588), MOD_FORWARD_BPS, FORWARD_TOLERANCE_BPS, "bps");
		checkAtMost(run + ": no bubble trail after the reset (mean bubble particles, ticks 560-588)", meanParticles(samples, 560, 588), NO_TRAIL_MAX_PARTICLES);
		checkStayedInLane(run, samples);
	}

	private void forwardDisabled(ClientGameTestContext ctx, TestServerContext server) {
		String run = "forward, mod disabled";
		boardNewBoat(ctx, server, FORWARD_START_X, EAST_YAW);
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.THIRD_PERSON_BACK));
		// 260 ticks is past the longest boost threshold (160 ticks), so a boost would show.
		List<Sample> samples = drive(ctx, run, o -> o.keyUp, 260, tick -> {
			if (tick == 250) {
				log("screenshot " + ctx.takeScreenshot("disabled-no-trail"));
			}
		});
		ctx.runOnClient(mc -> mc.options.setCameraType(CameraType.FIRST_PERSON));
		if (samples.size() < 260) {
			return;
		}

		logTrace(run, samples);
		checkNear(run + ": vanilla speed (ticks 70-110)", meanSpeed(samples, 70, 110), VANILLA_FORWARD_BPS, FORWARD_TOLERANCE_BPS, "bps");
		checkNear(run + ": still vanilla speed, no boost (ticks 220-260)", meanSpeed(samples, 220, 260), VANILLA_FORWARD_BPS, FORWARD_TOLERANCE_BPS, "bps");
		checkAtMost(run + ": no bubble trail (mean bubble particles, ticks 220-260)", meanParticles(samples, 220, 260), NO_TRAIL_MAX_PARTICLES);
		checkStayedInLane(run, samples);
	}

	private void forwardReenabled(ClientGameTestContext ctx, TestServerContext server) {
		String run = "forward, mod re-enabled";
		boardNewBoat(ctx, server, FORWARD_START_X, EAST_YAW);
		List<Sample> samples = drive(ctx, run, o -> o.keyUp, 120, tick -> { });
		if (samples.size() < 120) {
			return;
		}

		checkNear(run + ": base speed (ticks 70-110)", meanSpeed(samples, 70, 110), MOD_FORWARD_BPS, FORWARD_TOLERANCE_BPS, "bps");
	}

	private void reverse(ClientGameTestContext ctx, TestServerContext server, String run, double expectedBps) {
		boardNewBoat(ctx, server, REVERSE_START_X, EAST_YAW);
		// Under the shortest boost threshold, which doesn't apply to reverse anyway.
		List<Sample> samples = drive(ctx, run, o -> o.keyDown, 120, tick -> { });
		if (samples.size() < 120) {
			return;
		}

		checkNear(run + ": speed (ticks 60-110)", meanSpeed(samples, 60, 110), expectedBps, REVERSE_TOLERANCE_BPS, "bps");
		checkTrue(run + ": moves west (backwards)", samples.get(109).pos().x < samples.get(60).pos().x,
				"x " + fmt(samples.get(60).pos().x) + " -> " + fmt(samples.get(109).pos().x));
	}

	private void checkVersionCommand(ClientGameTestContext ctx) {
		String version = FabricLoader.getInstance().getModContainer("faster-boats")
				.map(c -> c.getMetadata().getVersion().getFriendlyString())
				.orElse("missing");
		String mcVersion = SharedConstants.getCurrentVersion().name();
		String[] parts = mcVersion.split("\\.");
		String drop = parts.length >= 2 ? parts[0] + "." + parts[1] : mcVersion;
		checkTrue("mod_version carries the running Minecraft drop", version.endsWith("+" + drop),
				"mod version " + version + ", Minecraft " + mcVersion + ", drop " + drop);

		ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
		typeCommand(ctx, "faster-boats version");
		String expected = "Faster Boats " + version;
		checkTrue("/faster-boats version replies in chat", waitForChat(ctx, expected), "expected chat line \"" + expected + "\", got " + chatLines(ctx));
		log("screenshot " + ctx.takeScreenshot("version-command"));
	}

	private void setEnabledThroughChat(ClientGameTestContext ctx, boolean enabled) {
		ctx.runOnClient(mc -> mc.gui.hud.getChat().clearMessages(false));
		typeCommand(ctx, "faster-boats enable " + enabled);
		String expected = "[faster-boats] " + (enabled ? "enabled" : "disabled");
		checkTrue("/faster-boats enable " + enabled + " replies in chat", waitForChat(ctx, expected), "expected chat line \"" + expected + "\", got " + chatLines(ctx));
		checkTrue("/faster-boats enable " + enabled + " sets the flag", FasterBoatsConfig.enabled == enabled, "FasterBoatsConfig.enabled=" + FasterBoatsConfig.enabled);
		if (!enabled) {
			log("screenshot " + ctx.takeScreenshot("disable-command"));
		}
	}

	// ---- Scene ----

	private void buildTrack(ClientGameTestContext ctx, TestServerContext server) {
		String ground = server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			StringBuilder sb = new StringBuilder();
			for (int y = -64; y <= -60; y++) {
				sb.append(y).append('=').append(blockId(level.getBlockState(new BlockPos(0, y, 0)).getBlock())).append(' ');
			}
			return sb.toString().trim();
		});
		log("test world column at x=0 z=0: " + ground);

		server.runCommand(String.format(Locale.ROOT, "forceload add %d %d %d %d",
				TRACK_MIN_X, -TRACK_HALF_WIDTH - 2, TRACK_MAX_X, TRACK_HALF_WIDTH + 2));

		// A fill only works once its chunks are loaded, so retry each segment until a block read confirms it.
		for (int x = TRACK_MIN_X; x <= TRACK_MAX_X; x += 64) {
			int x1 = x;
			int x2 = Math.min(x + 63, TRACK_MAX_X);
			String fill = String.format(Locale.ROOT, "fill %d %d %d %d %d %d minecraft:water",
					x1, WATER_BOTTOM_Y, -TRACK_HALF_WIDTH, x2, WATER_TOP_Y, TRACK_HALF_WIDTH);
			boolean filled = false;
			for (int attempt = 0; attempt < 100 && !filled; attempt++) {
				server.runCommand(fill);
				filled = server.computeOnServer(s -> isWater(s.overworld(), x1, WATER_BOTTOM_Y, -TRACK_HALF_WIDTH)
						&& isWater(s.overworld(), x2, WATER_TOP_Y, TRACK_HALF_WIDTH));
				if (!filled) {
					ctx.waitTicks(5);
				}
			}
		}

		int[] counts = new int[2]; // water, total
		String banks = server.computeOnServer(s -> {
			ServerLevel level = s.overworld();
			int water = 0;
			int total = 0;
			for (int x = TRACK_MIN_X; x <= TRACK_MAX_X; x += 13) {
				for (int z = -TRACK_HALF_WIDTH; z <= TRACK_HALF_WIDTH; z += TRACK_HALF_WIDTH) {
					for (int y = WATER_BOTTOM_Y; y <= WATER_TOP_Y; y++) {
						total++;
						if (isWater(level, x, y, z)) {
							water++;
						}
					}
				}
			}
			counts[0] = water;
			counts[1] = total;
			return "bank at z=" + (TRACK_HALF_WIDTH + 1) + ": "
					+ blockId(level.getBlockState(new BlockPos(100, WATER_TOP_Y, TRACK_HALF_WIDTH + 1)).getBlock())
					+ ", above the water: " + blockId(level.getBlockState(new BlockPos(100, WATER_TOP_Y + 1, 0)).getBlock());
		});
		checkTrue("track built", counts[0] == counts[1], counts[0] + "/" + counts[1] + " sampled channel blocks are water; " + banks);
	}

	private void boardNewBoat(ClientGameTestContext ctx, TestServerContext server, double x, float yaw) {
		ctx.getInput().releaseKey(o -> o.keyUp);
		ctx.getInput().releaseKey(o -> o.keyDown);
		server.runCommand("ride @a dismount");
		server.runCommand("kill @e[type=minecraft:oak_boat]");
		server.runCommand("kill @e[type=minecraft:item]");
		server.runCommand(String.format(Locale.ROOT, "tp @a %.1f %d %.1f", x, WATER_TOP_Y + 2, LANE_Z));
		server.runCommand(String.format(Locale.ROOT, "summon minecraft:oak_boat %.1f %.1f %.1f {Rotation:[%.1ff,0.0f]}", x, WATER_TOP_Y + 0.6, LANE_Z, yaw));
		server.runCommand("ride @p mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
		ctx.waitFor(mc -> mc.player != null && mc.player.getVehicle() instanceof AbstractBoat, 200);
		ctx.waitTicks(40); // let it settle on the water
		Vec3 pos = ctx.computeOnClient(mc -> mc.player.getVehicle().position());
		float boatYaw = ctx.computeOnClient(mc -> mc.player.getVehicle().getYRot());
		log(String.format(Locale.ROOT, "boarded a new boat at x=%.2f y=%.3f z=%.2f yaw=%.1f", pos.x, pos.y, pos.z, boatYaw));
	}

	// ---- Driving and measuring ----

	/** The boat right before a tick ({@code before}) and right after it. */
	private record Sample(int tick, Vec3 before, Vec3 pos, float yaw, int trailParticles) {
	}

	@FunctionalInterface
	private interface TickAction {
		void beforeTick(int tick);
	}

	/**
	 * Holds a key for {@code ticks} ticks and samples the boat around each one: sample i is tick i.
	 * The position is read right before and right after each tick, because some actions advance the
	 * game themselves (a screenshot waits at least one tick for its frame), so consecutive samples
	 * are not always exactly one tick apart.
	 */
	private List<Sample> drive(ClientGameTestContext ctx, String run, Function<Options, KeyMapping> key, int ticks, TickAction action) {
		List<Sample> samples = new ArrayList<>();
		ctx.getInput().holdKey(key);
		try {
			for (int tick = 0; tick < ticks; tick++) {
				action.beforeTick(tick);
				Vec3 before = ctx.computeOnClient(mc -> mc.player != null && mc.player.getVehicle() instanceof AbstractBoat boat ? boat.position() : null);
				ctx.waitTick();
				Sample s = before == null ? null : sample(ctx, tick, before);
				if (s == null) {
					fail(run + ": the player left the boat at tick " + tick);
					break;
				}
				samples.add(s);
			}
		} finally {
			ctx.getInput().releaseKey(key);
			ctx.getInput().releaseKey(o -> o.keyLeft);
			ctx.getInput().releaseKey(o -> o.keyRight);
		}
		return samples;
	}

	private static Sample sample(ClientGameTestContext ctx, int tick, Vec3 before) {
		return ctx.computeOnClient(mc -> {
			Entity vehicle = mc.player == null ? null : mc.player.getVehicle();
			if (!(vehicle instanceof AbstractBoat boat)) {
				return null;
			}
			return new Sample(tick, before, boat.position(), boat.getYRot(), trailParticleCount(mc));
		});
	}

	/** Horizontal speed during tick t, in blocks per second. */
	private static double speedAt(List<Sample> samples, int t) {
		Vec3 a = samples.get(t).before();
		Vec3 b = samples.get(t).pos();
		return Math.hypot(b.x - a.x, b.z - a.z) * 20.0;
	}

	/** Mean horizontal speed over ticks [from, to), in blocks per second, along the path actually travelled. */
	private static double meanSpeed(List<Sample> samples, int from, int to) {
		double distance = 0.0;
		for (int t = from; t < to; t++) {
			distance += speedAt(samples, t) / 20.0;
		}
		return distance / (to - from) * 20.0;
	}

	/** Mean number of live trail particles over ticks [from, to). */
	private static double meanParticles(List<Sample> samples, int from, int to) {
		double sum = 0.0;
		for (int t = from; t < to; t++) {
			sum += samples.get(t).trailParticles();
		}
		return sum / (to - from);
	}

	private void logTrace(String run, List<Sample> samples) {
		StringBuilder sb = new StringBuilder(run + ": speed per second (bps):");
		for (int t = 20; t <= samples.size(); t += 20) {
			sb.append(String.format(Locale.ROOT, " %d:%.2f", t / 20, meanSpeed(samples, t - 20, t)));
		}
		log(sb.toString());
	}

	private void checkStayedInLane(String run, List<Sample> samples) {
		double maxOffset = 0.0;
		for (Sample s : samples) {
			maxOffset = Math.max(maxOffset, Math.abs(s.pos().z - LANE_Z));
		}
		// The boat is 1.375 wide; touching a bank would slow it and spoil the speed checks.
		checkAtMost(run + ": stayed clear of the banks (max sideways drift, blocks)", maxOffset, TRACK_HALF_WIDTH - 1.0);
	}

	// ---- Chat ----

	private static void typeCommand(ClientGameTestContext ctx, String command) {
		ctx.getInput().pressKey(o -> o.keyChat);
		ctx.waitForScreen(ChatScreen.class);
		ctx.getInput().typeChars("/" + command);
		ctx.getInput().pressKey(InputConstants.KEY_RETURN);
		ctx.waitForScreen(null);
	}

	private static boolean waitForChat(ClientGameTestContext ctx, String line) {
		for (int i = 0; i < 40; i++) {
			if (ctx.computeOnClient(mc -> chatLines(mc).contains(line))) {
				return true;
			}
			ctx.waitTick();
		}
		return false;
	}

	private static List<String> chatLines(ClientGameTestContext ctx) {
		return ctx.computeOnClient(FasterBoatsClientGameTest::chatLines);
	}

	@SuppressWarnings("unchecked")
	private static List<String> chatLines(Minecraft mc) {
		try {
			if (chatMessagesField == null) {
				chatMessagesField = ChatComponent.class.getDeclaredField("allMessages");
				chatMessagesField.setAccessible(true);
			}
			List<GuiMessage> messages = (List<GuiMessage>) chatMessagesField.get(mc.gui.hud.getChat());
			return messages.stream().map(m -> m.content().getString()).toList();
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't read ChatComponent.allMessages", e);
		}
	}

	// ---- Particles ----

	/**
	 * Live bubble particles, the type the boost trail spawns. Other particles don't count: the water
	 * channel itself keeps spawning ambient UNDERWATER particles (vanilla WaterFluid.animateTick).
	 */
	@SuppressWarnings("unchecked")
	private static int trailParticleCount(Minecraft mc) {
		try {
			if (particleGroupsField == null) {
				particleGroupsField = ParticleEngine.class.getDeclaredField("particles");
				particleGroupsField.setAccessible(true);
				particleQueueField = ParticleGroup.class.getDeclaredField("particles");
				particleQueueField.setAccessible(true);
			}
			Map<?, ParticleGroup<?>> groups = (Map<?, ParticleGroup<?>>) particleGroupsField.get(mc.particleEngine);
			int bubbles = 0;
			for (ParticleGroup<?> group : groups.values()) {
				for (Object particle : (Collection<?>) particleQueueField.get(group)) {
					if (particle instanceof BubbleParticle) {
						bubbles++;
					}
				}
			}
			return bubbles;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("Can't read the particle engine's particles", e);
		}
	}

	// ---- Checks ----

	private void checkNear(String name, double measured, double expected, double tolerance, String unit) {
		boolean ok = Math.abs(measured - expected) <= tolerance;
		report(ok, String.format(Locale.ROOT, "%s: measured %.3f %s, expected %.3f ± %.3f", name, measured, unit, expected, tolerance));
	}

	private void checkRange(String name, double measured, double min, double max, String unit) {
		boolean ok = measured >= min && measured <= max;
		report(ok, String.format(Locale.ROOT, "%s: measured %.0f %s, expected %.0f-%.0f", name, measured, unit, min, max));
	}

	private void checkAtLeast(String name, double measured, double min) {
		report(measured >= min, String.format(Locale.ROOT, "%s: measured %.2f, expected >= %.2f", name, measured, min));
	}

	private void checkAtMost(String name, double measured, double max) {
		report(measured <= max, String.format(Locale.ROOT, "%s: measured %.2f, expected <= %.2f", name, measured, max));
	}

	private void checkTrue(String name, boolean ok, String detail) {
		report(ok, name + ": " + detail);
	}

	private void report(boolean ok, String message) {
		if (ok) {
			log("PASS " + message);
		} else {
			fail(message);
		}
	}

	private void fail(String message) {
		log("FAIL " + message);
		failures.add(message);
	}

	private static void log(String message) {
		LOGGER.info("[{}] {}", ID, message);
	}

	// ---- Helpers ----

	private static boolean isWater(ServerLevel level, int x, int y, int z) {
		return level.getBlockState(new BlockPos(x, y, z)).is(Blocks.WATER);
	}

	private static String blockId(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).toString();
	}

	private static String fmt(double value) {
		return String.format(Locale.ROOT, "%.2f", value);
	}
}
