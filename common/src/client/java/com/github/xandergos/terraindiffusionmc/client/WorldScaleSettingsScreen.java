package com.github.xandergos.terraindiffusionmc.client;

import com.github.xandergos.terraindiffusionmc.world.RiverMode;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleManager;
import com.github.xandergos.terraindiffusionmc.world.WorldScaleSelectionState;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
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

    private final Screen parentScreen;
    private EditBox scaleTextField;
    private StringWidget validationTextWidget;
    private StringWidget riverDescriptionWidget;
    private Button riverModeButton;
    private RiverMode riverMode = RiverMode.DEFAULT;

    public WorldScaleSettingsScreen(Screen parentScreen) {
        super(Component.translatable("terrain-diffusion-mc.world_settings.title"));
        this.parentScreen = parentScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        addCenteredTextWidget(this.title, centerX, 20, 0xFFFFFF);

        addCenteredTextWidget(DESCRIPTION_TEXT, centerX, centerY - 62, 0xAAAAAA);
        addCenteredTextWidget(LABEL_TEXT, centerX, centerY - 50, 0xFFFFFF);

        scaleTextField = new EditBox(this.font,
                centerX - TEXT_FIELD_WIDTH / 2, centerY - 38,
                TEXT_FIELD_WIDTH, TEXT_FIELD_HEIGHT,
                LABEL_TEXT);
        scaleTextField.setValue(String.valueOf(WorldScaleSelectionState.getPendingScaleOrDefault()));
        scaleTextField.setResponder(value -> validationTextWidget.setMessage(Component.empty()));
        this.addRenderableWidget(scaleTextField);
        this.setInitialFocus(scaleTextField);

        addCenteredTextWidget(RIVER_LABEL_TEXT, centerX, centerY - 8, 0xFFFFFF);

        riverMode = WorldScaleSelectionState.getPendingRiverModeOrDefault();
        riverModeButton = Button.builder(riverModeLabel(), button -> {
                    riverMode = riverMode.next();
                    riverModeButton.setMessage(riverModeLabel());
                    riverDescriptionWidget.setMessage(riverModeDescription());
                })
                .bounds(centerX - BUTTON_WIDTH, centerY + 4, BUTTON_WIDTH * 2, BUTTON_HEIGHT)
                .build();
        this.addRenderableWidget(riverModeButton);

        riverDescriptionWidget = new StringWidget(0, centerY + 28, this.width, 9,
                riverModeDescription(), this.font);
        this.addRenderableWidget(riverDescriptionWidget);

        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onDonePressed())
                .bounds(centerX - BUTTON_WIDTH - 5, centerY + 48, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(centerX + 5, centerY + 48, BUTTON_WIDTH, BUTTON_HEIGHT)
                .build());

        validationTextWidget = new StringWidget(0, centerY + 74, this.width, 9, Component.empty(), this.font);
        this.addRenderableWidget(validationTextWidget);
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
            applyWorldHeightForScale(selectedScale);
            WorldScaleSelectionState.setPendingScale(selectedScale);
            WorldScaleSelectionState.setPendingRiverMode(riverMode);
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
