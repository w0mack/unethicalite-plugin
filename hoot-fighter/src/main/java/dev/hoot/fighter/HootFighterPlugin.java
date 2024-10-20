package dev.hoot.fighter;

import com.google.inject.Provides;
import dev.unethicalite.api.entities.Players;
import dev.unethicalite.api.entities.TileItems;
import dev.unethicalite.api.game.Combat;
import dev.unethicalite.api.game.Game;
import dev.unethicalite.api.game.Worlds;
import dev.unethicalite.api.items.Inventory;
import dev.unethicalite.api.magic.Magic;
import dev.unethicalite.api.magic.Regular;
import dev.unethicalite.api.movement.Movement;
import dev.unethicalite.api.movement.Reachable;
import dev.unethicalite.api.plugins.LoopedPlugin;
import dev.unethicalite.api.widgets.Dialog;
import dev.unethicalite.api.widgets.Prayers;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.PluginDescriptor;
import org.pf4j.Extension;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@PluginDescriptor(
		name = "Hoot Fighter",
		description = "A simple auto fighter",
		enabledByDefault = false
)
@Slf4j
@Extension
public class HootFighterPlugin extends LoopedPlugin
{
	private ScheduledExecutorService executor;
	@Inject
	private HootFighterConfig config;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ClientThread clientThread;

	private WorldPoint startPoint;

	private List<TileItem> notOurItems = new ArrayList<>();

	@Override
	public void startUp() throws Exception
	{
		super.startUp();
		executor = Executors.newSingleThreadScheduledExecutor();
		executor.scheduleWithFixedDelay(() ->
		{
			try
			{
				if (!Game.isLoggedIn())
				{
					return;
				}

				if (config.quickPrayer() && !Prayers.isQuickPrayerEnabled())
				{
					Prayers.toggleQuickPrayer(true);
				}
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
		}, 0, 100, TimeUnit.MILLISECONDS);

		if (Game.isLoggedIn())
		{
			startPoint = Players.getLocal().getWorldLocation();
		}
	}

	@Provides
	public HootFighterConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(HootFighterConfig.class);
	}

	@Override
	public void shutDown() throws Exception
	{
		super.shutDown();
		if (executor != null)
		{
			executor.shutdown();
		}
	}

	@Override
	protected int loop()
	{

		if (Movement.isWalking())
		{
			return -4;
		}

		if (config.flick() && Prayers.isQuickPrayerEnabled())
		{
			Prayers.toggleQuickPrayer(false);
		}

		if (config.eat() && Combat.getHealthPercent() <= config.healthPercent())
		{
			List<String> foods = List.of(config.foods().split(","));
			Item food = Inventory.getFirst(x -> (x.getName() != null && foods.stream().anyMatch(a -> x.getName().contains(a)))
					|| (foods.contains("Any") && x.hasAction("Eat")));
			if (food != null)
			{
				food.interact("Eat");
				return -3;
			}
		}

		if (config.restore() && Prayers.getPoints() < 5)
		{
			Item restorePotion = Inventory.getFirst(x -> x.hasAction("Drink")
					&& (x.getName().contains("Prayer potion") || x.getName().contains("Super restore")));
			if (restorePotion != null)
			{
				restorePotion.interact("Drink");
				return -3;
			}
		}

		if (config.buryBones())
		{
			Item bones = Inventory.getFirst(x -> x.hasAction("Bury") || x.hasAction("Scatter"));
			if (bones != null)
			{
				bones.interact(0);
				return -1;
			}
		}

		Player local = Players.getLocal();
		List<String> itemsToLoot = List.of(config.loot().split(","));
		if (!Inventory.isFull())
		{
			TileItem loot = TileItems.getNearest(x ->
					x.getTile().getWorldLocation().distanceTo(local.getWorldLocation()) < config.attackRange()
							&& !notOurItems.contains(x)
							&& ((x.getName() != null && itemsToLoot.contains(x.getName())
							|| (config.lootValue() > -1 && itemManager.getItemPrice(x.getId()) * x.getQuantity() > config.lootValue())
							|| (config.untradables() && (!x.isTradable()) || x.hasInventoryAction("Destroy"))))
			);
			if (loot != null)
			{
				if (Regular.TELEKINETIC_GRAB.canCast())
				{
					log.info("telegrabbing " + loot.getName());
					Magic.cast(Regular.TELEKINETIC_GRAB, loot);
					return -2;
				}
				else if (!Reachable.isInteractable(loot.getTile()))
				{
					Movement.walkTo(loot.getTile().getWorldLocation());
					return -4;
				}
					loot.pickup();
					return -3;


			} else if (Regular.TELEKINETIC_GRAB.canCast() && config.noLoot())
			{
				clientThread.invoke(() -> hop());
				return -1;
			}
		}

		if (config.alching())
		{
			AlchSpell alchSpell = config.alchSpell();
			if (alchSpell.canCast())
			{
				List<String> alchItems = List.of(config.alchItems().split(","));
				Item alchItem = Inventory.getFirst(x -> x.getName() != null && alchItems.contains(x.getName()));
				if (alchItem != null)
				{
					Magic.cast(alchSpell.getSpell(), alchItem);
					return -1;
				}
			}
		}

		if (local.getInteracting() != null && !Dialog.canContinue())
		{
			return -1;
		}

		NPC mob = Combat.getAttackableNPC(x -> x.getName() != null
				&& x.getName().toLowerCase().contains(config.monster().toLowerCase()) && !x.isDead()
				&& x.getWorldLocation().distanceTo(local.getWorldLocation()) < config.attackRange()
		);
		if (mob == null)
		{
			if (startPoint == null)
			{
				log.info("No attackable monsters in area");
				return -1;
			}

			Movement.walkTo(startPoint);
			return -4;
		}

		if (!Reachable.isInteractable(mob))
		{
			Movement.walkTo(mob.getWorldLocation());
			return -4;
		}

		mob.interact("Attack");
		return -3;
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		String message = e.getMessage();
		if (message.contains("other players have dropped"))
		{
			var notOurs = TileItems.getAt(Players.getLocal().getWorldLocation(), x -> true);
			log.debug("{} are not our items", notOurs.stream().map(TileItem::getName).collect(Collectors.toList()));
			notOurItems.addAll(notOurs);
		}
	}

	private void hop() {

		if (Game.getState() != GameState.LOGGED_IN) {
			return;
		}

		Worlds.loadWorlds();
		HashSet<World> attempted = new HashSet<World>();
		attempted.add(Worlds.getCurrentWorld());

		EnumSet<WorldType> currentWorldTypes = Game.getClient().getWorldType();
		currentWorldTypes.remove(WorldType.PVP);
		currentWorldTypes.remove(WorldType.BOUNTY);
		currentWorldTypes.remove(WorldType.HIGH_RISK);
		currentWorldTypes.remove(WorldType.SKILL_TOTAL);
		currentWorldTypes.remove(WorldType.LAST_MAN_STANDING);

		int totalLevel = Game.getClient().getTotalLevel();

		World world = null;
		try {
			do {

				world = Worlds.getRandom((w) -> !attempted.contains(w));
				attempted.add(world);

				EnumSet<WorldType> types = world.getTypes().clone();
				types.remove(WorldType.BOUNTY);
				types.remove(WorldType.LAST_MAN_STANDING);

				if (types.contains(WorldType.SKILL_TOTAL)) {
					try {
						int totalRequirement = Integer.parseInt(world.getActivity().substring(0, world.getActivity().indexOf(" ")));
						if (totalLevel >= totalRequirement) {
							types.remove(WorldType.SKILL_TOTAL);
						}
					} catch (NumberFormatException ex) {
						log.warn("Failed to parse total level requirement", ex);
					}
				}
				if (world.getPlayerCount() > 1950) {
					continue;
				}

				if (currentWorldTypes.equals(types)) {
					break;
				}

				world = null;
			}
			while (world == null || !(Game.getState() == GameState.LOGGED_IN));
		} catch (Exception e) { log.error("Error looping worlds", e); }

		if (world == null)
		{
			log.info("Couldn't find a world after {} attempts", attempted.size());
			String chatMessage = new ChatMessageBuilder()
					.append(ChatColorType.HIGHLIGHT)
					.append("Couldn't find a world to quick hop to. attempts " + attempted.size())
					.build();

			log.info(chatMessage);
		}
		else
		{
			String chatMessage = new ChatMessageBuilder()
					.append(ChatColorType.NORMAL)
					.append("Hopping to " + world.getId())
					.build();

			log.info(chatMessage);

			final World rsWorld = Game.getClient().createWorld();
			rsWorld.setActivity(world.getActivity());
			rsWorld.setAddress(world.getAddress());
			rsWorld.setId(world.getId());
			rsWorld.setPlayerCount(world.getPlayerCount());
			rsWorld.setLocation(world.getLocation());
			rsWorld.setTypes(world.getTypes());

			if (!Worlds.isHopperOpen())
			{
				Worlds.openHopper();
				log.info("Opening hopper");

			}
			Worlds.hopTo(rsWorld);

		}
	}

}
