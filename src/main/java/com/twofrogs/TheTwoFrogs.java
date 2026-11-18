package com.twofrogs;

import com.twofrogs.command.BuildCommand;
import com.twofrogs.command.SpeedCommand;
import com.twofrogs.command.TwoFrogsCommand;
import com.twofrogs.extras.ExtrasManager;
import com.twofrogs.frog.AgitationHints;
import com.twofrogs.frog.EscapeControl;
import com.twofrogs.frog.PlacementGuard;
import com.twofrogs.frog.RageBehavior;
import com.twofrogs.frog.SoothingHandler;
import com.twofrogs.frog.SpecialFrogManager;
import com.twofrogs.frog.SpeedProbe;
import com.twofrogs.state.TwoFrogsState;
import com.twofrogs.state.WorldPlanState;
import com.twofrogs.world.HabitatSpawner;
import com.twofrogs.world.SitePlanner;
import com.twofrogs.world.structure.StructureBuilder;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.twofrogs.altar.AltarRitual;
import com.twofrogs.command.AltarCommand;
import com.twofrogs.clue.ClueGenerator;
import com.twofrogs.clue.ClueRecovery;
import com.twofrogs.command.ClueCommand;
import com.twofrogs.command.EncounterCommand;
import com.twofrogs.encounter.EncounterDirector;

public class TheTwoFrogs implements ModInitializer {

	public static final String MOD_ID = "the-two-frogs";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			TwoFrogsCommand.register(dispatcher);
			BuildCommand.register(dispatcher);
			SpeedCommand.register(dispatcher);
            AltarCommand.register(dispatcher);
			ClueCommand.register(dispatcher);
			EncounterCommand.register(dispatcher);
		});

		SpecialFrogManager.register();
		PlacementGuard.register();
		SoothingHandler.register();
		ExtrasManager.register();
		RageBehavior.register();
		EscapeControl.register();
		AgitationHints.register();
		SpeedProbe.register();
        AltarRitual.register();
		ClueRecovery.register();
		EncounterDirector.register();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TwoFrogsState state = TwoFrogsState.get(server);
			LOGGER.info("The Two Frogs: world state loaded. Frog A assigned: {}, Frog B assigned: {}",
					state.getFrogUuid(TwoFrogsState.FrogRole.A).isPresent(),
					state.getFrogUuid(TwoFrogsState.FrogRole.B).isPresent());

			try {
				// Fresh world: plan the sites and create the habitat once.
				boolean fresh = !state.hasHabitat()
						&& state.getFrogUuid(TwoFrogsState.FrogRole.A).isEmpty()
						&& state.getFrogUuid(TwoFrogsState.FrogRole.B).isEmpty();
				WorldPlanState plan = WorldPlanState.get(server);
				if (fresh) {
					if (!plan.isPlanned()) {
						SitePlanner.PlanResult result = SitePlanner.plan(server);
						LOGGER.info("The Two Frogs: {}", result.message());
					}
					if (plan.isPlanned() && !plan.isFrogsSpawned()) {
						LOGGER.info("The Two Frogs: {}", HabitatSpawner.spawnFrogs(server));
					}
				}

				// Build any planned structures that do not exist yet (also covers older worlds).
				if (plan.isPlanned()) {
                    StructureBuilder.buildPending(server);
                    ClueGenerator.generatePending(server);
				}
			} catch (Exception e) {
				LOGGER.error("The Two Frogs: world setup failed", e);
			}
		});

		LOGGER.info("The Two Frogs initialized.");
	}
}