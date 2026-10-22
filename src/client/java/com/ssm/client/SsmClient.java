package com.ssm.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

public class SsmClient implements ClientModInitializer {

	// Delay (in ticks) between the axe hit and the mace hit, inclusive.
	private static final int MIN_DELAY_TICKS = 2;
	private static final int MAX_DELAY_TICKS = 3;

	private static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("ssm", "main"));

	private static KeyMapping stunSlamKey;
	private static KeyMapping autoToggleKey;

	// when on hitting some one with shield auto ss's
	private boolean autoMode =true;

	// -1 = idle, otherwise ticks remaining until swap to the mace and hit.
	private int ticksUntilSlam = -1;
	private int maceSlot = -1;

    // auto trigger start mid tick (before tick handler) so  skip 1 countdown
	private boolean skipNextCountdown = false;

	// True while we are sending our own attack so it doesnt re trigger
	private boolean attacking = false;

	@Override
	public void onInitializeClient() {
		stunSlamKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.ssm.stun_slam",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_R,
				CATEGORY
		));

		autoToggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.ssm.toggle_auto",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				CATEGORY
		));

		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

		AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) -> onAttack(player, entity));
	}

	private InteractionResult onAttack(Player player, Entity target) {
		Minecraft mc = Minecraft.getInstance();

		if (!autoMode || attacking || ticksUntilSlam >= 0) {
			return InteractionResult.PASS;
		}

		if (mc.player == null || player != mc.player) {
			return InteractionResult.PASS;
		}
		if (!(target instanceof LivingEntity living) || !living.isBlocking()) {
			return InteractionResult.PASS;
		}

	    //start () doies axe hit itself so cancel vanilla attack so no silly  willy double hitting.
		return start(mc, mc.player, target, true) ? InteractionResult.FAIL : InteractionResult.PASS;
	}

	private void onTick(Minecraft mc) {
		LocalPlayer player = mc.player;

		if (player == null || mc.gameMode == null || mc.screen != null) {
			ticksUntilSlam = -1;
			skipNextCountdown = false;
			while (stunSlamKey.consumeClick()) { /* discard */ }
			while (autoToggleKey.consumeClick()) { /*discard*/ }
			return;
		}

		while (autoToggleKey.consumeClick()) {
			autoMode = !autoMode;
			player.displayClientMessage(Component.literal("Auto stun slam " + (autoMode ? "ON" : "of")), true);
		}

		// Count down to the mace hit.
		if (skipNextCountdown) {
			skipNextCountdown = false;
		} else if (ticksUntilSlam > 0 && --ticksUntilSlam == 0) {
			slam(mc, player);
			ticksUntilSlam = -1;
		}

		// manual Key pressed: start a new sequence (ignored if one is already running).
		while (stunSlamKey.consumeClick()) {
			if (ticksUntilSlam < 0) {
				Entity target = getTarget(mc);
				if (target != null) {
					start(mc, player, target, false);
				}
			}
		}
	}

	/**
	 * axe hit now mace hit in 2-3 ticks
	 *
	 * @param fromHook true when started by the attack hook ( no error message and vanilla swing is re used
	 * @return true if sequence started
	 */
	private boolean start(Minecraft mc, LocalPlayer player, Entity target, boolean fromHook) {
		// Prefer the axe you're already holding, otherwise the first axe in the hotbar.
		int selected = player.getInventory().getSelectedSlot();
		int axeSlot = player.getInventory().getItem(selected).is(ItemTags.AXES)
				? selected
				: findHotbarSlot(player, s -> s.is(ItemTags.AXES));
		int mace = findHotbarSlot(player, s -> s.is(Items.MACE));

		if (axeSlot < 0 || mace < 0) {
			if (!fromHook) {
				player.displayClientMessage(Component.literal("Stun slam: need an axe and a mace in your hotbar"), true);
			}
			return false;
		}

		//  axe hit disables shield
		player.getInventory().setSelectedSlot(axeSlot);
		attack(mc, player, target, !fromHook);

		//  scheduled random 2-3 tick from now
		maceSlot = mace;
		ticksUntilSlam = ThreadLocalRandom.current().nextInt(MIN_DELAY_TICKS, MAX_DELAY_TICKS + 1);
		skipNextCountdown = fromHook;
		return true;
	}

	private void slam(Minecraft mc, LocalPlayer player) {
		player.getInventory().setSelectedSlot(maceSlot);

		Entity target = getTarget(mc);
		if (target == null) {
			attack(mc, player, target, true);
		} else {
			//debug: crosshair left the target during delay so mace was skipped
			player.displayClientMessage(Component.literal("slam: no target"), true);
		}
	}

	private void attack(Minecraft mc, LocalPlayer player, Entity target, boolean swing) {
		attacking = true;
		try {
			mc.gameMode.attack(player, target);
		} finally {
			attacking = false;
		}
		if (swing) {
			player.swing(InteractionHand.MAIN_HAND);
		}
	}

	private static Entity getTarget(Minecraft mc) {
		return mc.hitResult instanceof EntityHitResult hit ? hit.getEntity() : null;
	}

	private static int findHotbarSlot(LocalPlayer player, Predicate<ItemStack> matcher) {
		for (int i = 0; i < 9; i++) {
			if (matcher.test(player.getInventory().getItem(i))) {
				return i;
			}
		}
		return -1;
	}
}
