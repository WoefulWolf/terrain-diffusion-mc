package com.github.xandergos.terraindiffusionmc.pipeline;

/**
 * What the raw climate channels imply about a place, derived once.
 *
 * <p>Temperature and rainfall alone do not say whether trees can grow: the same 600 mm
 * feeds a forest in a cool maritime summer and fails in a hot one that evaporates it,
 * and it means nothing at all where the season above growing temperature is too short
 * to use it. These are the quantities that answer that, and everything that reads
 * climate — the classifier, and the vanilla-parameter synthesis the compatibility layer
 * hands to other mods — derives them here rather than separately, so the two can never
 * disagree about the same cell.
 */
public record DerivedClimate(
        /** Temperature's seasonal standard deviation in Celsius. */
        float tStd,
        /** Potential evapotranspiration: how much water the year's heat can lift. */
        float pet,
        /** Rainfall against that demand. Below ~1 the place spends the year in deficit. */
        float aridity,
        /** Aridity discounted for unreliable rain, which trees cannot bank on. */
        float treeMoisture,
        /** Days a year above the 5 C growing threshold. */
        float growingSeason,
        /** Moisture trees can actually use, worthless where the season is too short. */
        float effTreeMoisture) {

    /**
     * @param temp    mean temperature in Celsius
     * @param tSeason temperature seasonality, in the climate field's own units
     * @param precip  annual precipitation in millimetres
     * @param pCV     precipitation coefficient of variation
     */
    public static DerivedClimate of(float temp, float tSeason, float precip, float pCV) {
        float tStd = tSeason / 100f;
        // Summer heat, not the annual mean, drives evaporation, so the swing counts.
        float tEff = Math.max(0f, temp + 0.5f * tStd);
        float pet = Math.max(250f, 250f + 25f * tEff + 0.7f * tEff * tEff);
        float aridity = precip / Math.max(1f, pet);
        float seasonPenalty = 1f - 0.35f * Math.min(1f, pCV / 100f);
        float treeMoisture = aridity * seasonPenalty;

        // Days above 5 C, read off a sine year: with no seasonal swing the year is
        // wholly above or wholly below, otherwise the arcsine gives the share of it
        // spent on the warm side of the threshold.
        float amplitude = tStd * 1.414f;
        float growingSeason;
        if (amplitude < 0.1f) {
            growingSeason = temp > 5f ? 365f : 0f;
        } else {
            float x = (5f - temp) / amplitude;
            if (x <= -1f) growingSeason = 365f;
            else if (x >= 1f) growingSeason = 0f;
            else growingSeason = 365f * (0.5f - (float) Math.asin(Math.max(-1f, Math.min(1f, x))) / (float) Math.PI);
        }

        float gsFactor = Math.max(0f, Math.min(1f, (growingSeason - 60f) / (150f - 60f)));
        float effTreeMoisture = treeMoisture * gsFactor;

        return new DerivedClimate(tStd, pet, aridity, treeMoisture, growingSeason, effTreeMoisture);
    }
}
