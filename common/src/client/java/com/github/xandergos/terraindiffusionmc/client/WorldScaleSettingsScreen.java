package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.world.CaveParameters;
import com.github.xandergos.terraindiffusionmc.world.LatitudeParameters;
import com.github.xandergos.terraindiffusionmc.world.RiverMode;
import com.github.xandergos.terraindiffusionmc.world.RiverParameters;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleSelectionState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * World creation settings screen for selecting the initial terrain scale of a world.
 */
public final class WorldScaleSettingsScreen extends Screen {
    private static final int TEXT_FIELD_WIDTH = 80;
    private static final int TEXT_FIELD_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 80;
    private static final int BUTTON_HEIGHT = 20;

    private static final Component LABEL_TEXT = Component.literal("World Scale");
    private static final Component DESCRIPTION_TEXT = Component.literal("Enter an integer value (1-6)");
    private static final Component ERROR_TEXT = Component.literal("Scale must be an integer between 1 and 6")
            .withStyle(ChatFormatting.RED);

    private static final Component RIVER_LABEL_TEXT = Component.literal("Rivers");
    private static final Component RIVER_ERROR_TEXT = Component.literal("River settings must be numbers")
            .withStyle(ChatFormatting.RED);

    private static final Component CAVE_LABEL_TEXT = Component.literal("Caves");
    private static final Component CAVE_ERROR_TEXT = Component.literal("Cave settings must be whole numbers")
            .withStyle(ChatFormatting.RED);

    private static final Component CLIMATE_LABEL_TEXT = Component.literal("Climate");
    private static final Component CLIMATE_ERROR_TEXT = Component.literal("Climate settings must be whole numbers")
            .withStyle(ChatFormatting.RED);

    private final Screen parentScreen;
    private EditBox scaleTextField;
    private StringWidget validationTextWidget;
    private Button riverModeButton;
    private RiverMode riverMode = RiverMode.DEFAULT;
    private EditBox riverRarityField;
    private EditBox riverSourceField;
    private EditBox riverWidthField;
    private EditBox riverDepthField;
    private EditBox riverWidthGrowthField;
    private EditBox riverBankHeightField;
    private EditBox riverLakeSizeField;
    private EditBox riverLakeDepthField;
    private EditBox riverWobbleField;
    private EditBox riverBedReliefField;
    private EditBox caveSmallSealField;
    private EditBox caveLargeSealField;
    private EditBox latitudePoleDistanceField;
    private EditBox latitudeStartField;
    private EditBox latitudeStrengthField;

    public WorldScaleSettingsScreen(Screen parentScreen) {
        super(Component.translatable("terrain-diffusion-mc.world_settings.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        addCenteredTextWidget(this.title, centerX, 20, 0xFFFFFF);

        addCenteredTextWidget(LABEL_TEXT, centerX, centerY - 120, 0xFFFFFF);

        scaleTextField = new EditBox(this.font,
                centerX - TEXT_FIELD_WIDTH / 2, centerY - 110,
                TEXT_FIELD_WIDTH, 18,
                LABEL_TEXT);
        scaleTextField.setValue(String.valueOf(WorldScaleSelectionState.getPendingScaleOrDefault()));
        scaleTextField.setResponder(value -> validationTextWidget.setMessage(Component.empty()));
        scaleTextField.setTooltip(Tooltip.create(DESCRIPTION_TEXT));
        this.addRenderableWidget(scaleTextField);
        this.setInitialFocus(scaleTextField);

        // Each category fills its own column of fields, so the screen stays short
        // enough to never need scrolling.
        int riversCenter = centerX - 160;
        int cavesCenter = centerX;
        int climateCenter = centerX + 160;

        addCenteredTextWidget(RIVER_LABEL_TEXT, riversCenter, centerY - 82, 0xFFFFFF);

        riverMode = WorldScaleSelectionState.getPendingRiverModeOrDefault();
        riverModeButton = Button.builder(riverModeLabel(), button -> {
                    riverMode = riverMode.next();
                    riverModeButton.setMessage(riverModeLabel());
                    riverModeButton.setTooltip(Tooltip.create(riverModeDescription()));
                })
                .bounds(riversCenter - BUTTON_WIDTH, centerY - 72, BUTTON_WIDTH * 2, BUTTON_HEIGHT)
                .build();
        riverModeButton.setTooltip(Tooltip.create(riverModeDescription()));
        this.addRenderableWidget(riverModeButton);

        RiverParameters p = WorldScaleSelectionState.getPendingRiverParametersOrDefault();
        int left = riversCenter - 75, right = riversCenter + 3;
        riverRarityField = addLabeledField(left, centerY - 38, "Rarity", String.valueOf(p.mainChannelCells),
                "How much land must drain together before a river forms at all. "
                        + "Higher: fewer, rarer rivers. Lower: rivers everywhere.");
        riverSourceField = addLabeledField(right, centerY - 38, "Source size", String.valueOf(p.headwaterCells),
                "How small a stream can be at its source. Lower: springs start higher in the "
                        + "mountains and rivers run longer. Higher: rivers appear further downhill, "
                        + "already grown.");
        riverWidthField = addLabeledField(left, centerY - 8, "Max width", String.valueOf(p.maxWidthBlocks),
                "The widest a river can grow, in blocks. Higher: major rivers become enormous. "
                        + "Lower: even the biggest stay modest.");
        riverDepthField = addLabeledField(right, centerY - 8, "Max depth", String.valueOf(p.maxDepthBlocks),
                "The deepest a river can carve, in blocks. Higher: deep gorges under big rivers. "
                        + "Lower: everything stays shallow.");
        riverWidthGrowthField = addLabeledField(left, centerY + 22, "Width growth", String.valueOf(p.widthExponent),
                "How quickly a river widens as streams join it. Higher: wide soon after the "
                        + "source. Lower: narrow for most of its run.");
        riverBankHeightField = addLabeledField(right, centerY + 22, "Bank height", String.valueOf(p.freeboardBlocks),
                "How high the banks stand above the water, in blocks. Higher: rivers sit sunken "
                        + "below the land. Lower: water sits nearly level with it.");
        riverLakeSizeField = addLabeledField(left, centerY + 52, "Lake size", String.valueOf(p.lakeMinCells),
                "The smallest hollow that fills as a lake instead of the river carving through. "
                        + "Higher: only large basins become lakes. Lower: many small ponds.");
        riverLakeDepthField = addLabeledField(right, centerY + 52, "Lake depth", String.valueOf(p.lakeDepthBlocks),
                "How deep lakes are dug, in blocks. Higher: deep swimmable lakes. "
                        + "Lower: shallow sheets of water.");
        riverWobbleField = addLabeledField(left, centerY + 82, "Bank wobble", String.valueOf(p.edgeWobbleBlocks),
                "How ragged the shorelines of big rivers are, in blocks. Higher: wild, irregular "
                        + "banks. Lower: smooth, even curves.");
        riverBedReliefField = addLabeledField(right, centerY + 82, "Bed relief", String.valueOf(p.bedReliefBlocks),
                "How bumpy river and lake floors are, in blocks. Higher: underwater dunes and "
                        + "hollows. Lower: flat floors.");

        addCenteredTextWidget(CAVE_LABEL_TEXT, cavesCenter, centerY - 82, 0xFFFFFF);

        CaveParameters c = WorldScaleSelectionState.getPendingCaveParametersOrDefault();
        int cavesLeft = cavesCenter - 75, cavesRight = cavesCenter + 3;
        caveSmallSealField = addLabeledField(cavesLeft, centerY - 38, "Tunnel cover", String.valueOf(c.smallSealBlocks),
                "How deep small cave tunnels and ravines must stay below gentle ground, in "
                        + "blocks. They still surface in craggy or rocky country. "
                        + "0: they may break the surface anywhere, like vanilla.");
        caveLargeSealField = addLabeledField(cavesRight, centerY - 38, "Cavern cover", String.valueOf(c.largeSealBlocks),
                "How deep big caverns and their wide mouths must stay below the surface, in "
                        + "blocks. Rare mouths still open in crevasse, karst and canyon country. "
                        + "0: big openings appear anywhere, like vanilla.");

        addCenteredTextWidget(CLIMATE_LABEL_TEXT, climateCenter, centerY - 82, 0xFFFFFF);

        LatitudeParameters lat = WorldScaleSelectionState.getPendingLatitudeParametersOrDefault();
        int climateLeft = climateCenter - 75, climateRight = climateCenter + 3;
        latitudePoleDistanceField = addLabeledField(climateLeft, centerY - 38, "Pole distance",
                String.valueOf(lat.equatorPoleBlocks),
                "Blocks between the equator and a pole. The bands repeat forever, so "
                        + "travelling past a pole starts warming again. Higher: broad climate "
                        + "belts and long journeys. Lower: quick change with travel.");
        latitudeStartField = addLabeledField(climateRight, centerY - 38, "Start latitude",
                String.valueOf(lat.startLatitudeDeg),
                "Where the spawn sits between the equator (0) and the north pole (90). "
                        + "At the default 45, south is warmer and north is colder.");
        latitudeStrengthField = addLabeledField(climateLeft, centerY - 8, "Band strength",
                String.valueOf(lat.bandStrengthC),
                "How strongly latitude sways temperature, in degrees Celsius at the equator "
                        + "and poles. Around 25 gives frozen poles and tropical equator. "
                        + "0: no banding, climate ignores position as before.");

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onDonePressed())
                .bounds(centerX - BUTTON_WIDTH - 5, centerY + 116, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(centerX + 5, centerY + 116, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        validationTextWidget = new StringWidget(0, centerY + 140, this.width, 9, Component.empty(), this.font);
        this.addRenderableWidget(validationTextWidget);
    }

    /**
     * One labelled field of the settings grid, prefilled with the world default and
     * carrying its plain-language explanation as a hover tooltip.
     */
    private EditBox addLabeledField(int x, int y, String label, String value, String description) {
        addCenteredTextWidget(Component.literal(label), x + 36, y - 10, 0xAAAAAA);
        EditBox field = new EditBox(this.font, x, y, 72, 18, Component.literal(label));
        field.setValue(value);
        field.setResponder(v -> validationTextWidget.setMessage(Component.empty()));
        field.setTooltip(Tooltip.create(Component.literal(description)));
        this.addRenderableWidget(field);
        return field;
    }

    private Component riverModeLabel() {
        String name = switch (riverMode) {
            case OFF -> "Off";
            case FAST -> "Fast";
            case DETAILED -> "Detailed";
        };
        return Component.literal("Rivers: " + name);
    }

    /** One line on what the choice costs, since the trade is not obvious from the name. */
    private Component riverModeDescription() {
        String text = switch (riverMode) {
            case OFF -> "No rivers.";
            case FAST -> "Short, frequent pauses. Smaller rivers.";
            case DETAILED -> "Longer but rarer pauses. Allows major rivers.";
        };
        return Component.literal(text).copy().withStyle(style -> style.withColor(0xAAAAAA));
    }

    /**
     * Adds a centered StringWidget at the given screen-center x and y position.
     */
    private void addCenteredTextWidget(Component text, int centerX, int y, int color) {
        int textWidth = this.font.width(text);
        MutableComponent coloredText = text.copy().withStyle(style -> style.withColor(color));
        StringWidget widget = new StringWidget(centerX - textWidth / 2, y, textWidth, 9, coloredText, this.font);
        this.addRenderableWidget(widget);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parentScreen);
        }
    }

    /**
     * Parses and validates the chosen scale, then stores it as a pending world-creation value.
     */
    private void onDonePressed() {
        String rawScaleValue = scaleTextField.getValue().trim();
        if (rawScaleValue.isEmpty()) {
            validationTextWidget.setMessage(ERROR_TEXT);
            return;
        }
        try {
            int selectedScale = Integer.parseInt(rawScaleValue);
            if (selectedScale < 1 || selectedScale > WorldScaleManager.MAX_SCALE) {
                validationTextWidget.setMessage(ERROR_TEXT);
                return;
            }

            RiverParameters riverParameters;
            try {
                // The constructor clamps to sane bounds, so wild values are tamed here
                // rather than rejected.
                riverParameters = new RiverParameters(
                        Integer.parseInt(riverRarityField.getValue().trim()),
                        Integer.parseInt(riverSourceField.getValue().trim()),
                        Integer.parseInt(riverWidthField.getValue().trim()),
                        Integer.parseInt(riverDepthField.getValue().trim()),
                        Float.parseFloat(riverWidthGrowthField.getValue().trim()),
                        Float.parseFloat(riverBankHeightField.getValue().trim()),
                        Integer.parseInt(riverLakeSizeField.getValue().trim()),
                        Float.parseFloat(riverLakeDepthField.getValue().trim()),
                        Float.parseFloat(riverWobbleField.getValue().trim()),
                        Float.parseFloat(riverBedReliefField.getValue().trim()));
            } catch (NumberFormatException exception) {
                validationTextWidget.setMessage(RIVER_ERROR_TEXT);
                return;
            }

            CaveParameters caveParameters;
            try {
                caveParameters = new CaveParameters(
                        Integer.parseInt(caveSmallSealField.getValue().trim()),
                        Integer.parseInt(caveLargeSealField.getValue().trim()));
            } catch (NumberFormatException exception) {
                validationTextWidget.setMessage(CAVE_ERROR_TEXT);
                return;
            }

            LatitudeParameters latitudeParameters;
            try {
                latitudeParameters = new LatitudeParameters(
                        Integer.parseInt(latitudePoleDistanceField.getValue().trim()),
                        Integer.parseInt(latitudeStartField.getValue().trim()),
                        Integer.parseInt(latitudeStrengthField.getValue().trim()));
            } catch (NumberFormatException exception) {
                validationTextWidget.setMessage(CLIMATE_ERROR_TEXT);
                return;
            }

            applyWorldHeightForScale(selectedScale);
            WorldScaleSelectionState.setPendingScale(selectedScale);
            WorldScaleSelectionState.setPendingRiverMode(riverMode);
            WorldScaleSelectionState.setPendingRiverParameters(riverParameters);
            WorldScaleSelectionState.setPendingCaveParameters(caveParameters);
            WorldScaleSelectionState.setPendingLatitudeParameters(latitudeParameters);
            onClose();
        } catch (NumberFormatException exception) {
            validationTextWidget.setMessage(ERROR_TEXT);
        }
    }

    /**
     * Applies a pre-registered dimension type variant for the chosen scale. The same swap
     * runs again when the world is actually created, in case the preset changes after this.
     */
    private void applyWorldHeightForScale(int selectedScale) {
        if (parentScreen instanceof CreateWorldScreen createWorldScreen) {
            WorldScaleDimensions.apply(createWorldScreen, selectedScale);
        }
    }
}
