package de.rki.coronawarnapp.risk

import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import com.google.android.gms.nearby.exposurenotification.ExposureSummary
import de.rki.coronawarnapp.CoronaWarnApplication
import de.rki.coronawarnapp.R
import de.rki.coronawarnapp.appconfig.AppConfigProvider
import de.rki.coronawarnapp.exception.RiskLevelCalculationException
import de.rki.coronawarnapp.nearby.ENFClient
import de.rki.coronawarnapp.nearby.InternalExposureNotificationClient
import de.rki.coronawarnapp.notification.NotificationHelper
import de.rki.coronawarnapp.risk.RiskLevel.UNKNOWN_RISK_INITIAL
import de.rki.coronawarnapp.risk.RiskLevel.UNKNOWN_RISK_OUTDATED_RESULTS
import de.rki.coronawarnapp.server.protocols.internal.AttenuationDurationOuterClass.AttenuationDuration
import de.rki.coronawarnapp.storage.LocalData
import de.rki.coronawarnapp.storage.RiskLevelRepository
import de.rki.coronawarnapp.util.TimeAndDateExtensions.millisecondsToHours
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.round

@Singleton
class DefaultRiskLevels @Inject constructor(
    private val appConfigProvider: AppConfigProvider,
    private val enfClient: ENFClient
) : RiskLevels {

    override fun updateRepository(riskLevel: RiskLevel, time: Long) {
        val rollbackItems = mutableListOf<RollbackItem>()
        try {
            Timber.tag(TAG).v("Update the risk level with $riskLevel")
            val lastCalculatedRiskLevelScoreForRollback =
                RiskLevelRepository.getLastCalculatedScore()
            updateRiskLevelScore(riskLevel)
            rollbackItems.add {
                updateRiskLevelScore(lastCalculatedRiskLevelScoreForRollback)
            }

            // risk level calculation date update
            val lastCalculatedRiskLevelDate = LocalData.lastTimeRiskLevelCalculation()
            LocalData.lastTimeRiskLevelCalculation(time)
            rollbackItems.add {
                LocalData.lastTimeRiskLevelCalculation(lastCalculatedRiskLevelDate)
            }
        } catch (error: Exception) {
            Timber.tag(TAG).e(error, "Updating the RiskLevelRepository failed.")

            try {
                Timber.tag(TAG).d("Initiate Rollback")
                for (rollbackItem: RollbackItem in rollbackItems) rollbackItem.invoke()
            } catch (rollbackException: Exception) {
                Timber.tag(TAG).e(rollbackException, "RiskLevelRepository rollback failed.")
            }

            throw error
        }
    }

    override fun calculationNotPossibleBecauseOfOutdatedResults(): Boolean {
        // if the last calculation is longer in the past as the defined threshold we return the stale state
        val timeSinceLastDiagnosisKeyFetchFromServer =
            TimeVariables.getTimeSinceLastDiagnosisKeyFetchFromServer()
                ?: throw RiskLevelCalculationException(
                    IllegalArgumentException(
                        "Time since last exposure calculation is null"
                    )
                )
        /** we only return outdated risk level if the threshold is reached AND the active tracing time is above the
        defined threshold because [UNKNOWN_RISK_INITIAL] overrules [UNKNOWN_RISK_OUTDATED_RESULTS] */
        return timeSinceLastDiagnosisKeyFetchFromServer.millisecondsToHours() >
            TimeVariables.getMaxStaleExposureRiskRange() && isActiveTracingTimeAboveThreshold()
    }

    override fun calculationNotPossibleBecauseNoKeys() =
        (TimeVariables.getLastTimeDiagnosisKeysFromServerFetch() == null).also {
            if (it) {
                Timber.tag(TAG)
                    .v("No last time diagnosis keys from server fetch timestamp was found")
            }
        }

    override suspend fun calculationNotPossibleBecauseTracingIsOff() =
        // this applies if tracing is not activated
        !enfClient.isTracingEnabled.first()

    override suspend fun isIncreasedRisk(): Boolean {
        val lastExposureSummary = getNewExposureSummary()
        val appConfiguration = appConfigProvider.getAppConfig()
        Timber.tag(TAG).v("Retrieved configuration from backend")
        // custom attenuation parameters to weight the attenuation
        // values provided by the Google API
        val attenuationParameters = appConfiguration.attenuationDuration
        // these are the defined risk classes. They will divide the calculated
        // risk score into the low and increased risk
        val riskScoreClassification = appConfiguration.riskScoreClasses

        // calculate the risk score based on the values collected by the Google EN API and
        // the backend configuration
        val riskScore = calculateRiskScore(
            attenuationParameters,
            lastExposureSummary
        ).also {
            Timber.tag(TAG).v("Calculated risk with the given config: $it")
        }

        // get the high risk score class
        val highRiskScoreClass =
            riskScoreClassification.riskClassesList.find { it.label == "HIGH" }
                ?: throw RiskLevelCalculationException(IllegalStateException("No high risk score class found"))

        // if the calculated risk score is above the defined level threshold we return the high level risk score
        if (withinDefinedLevelThreshold(
                riskScore,
                highRiskScoreClass.min,
                highRiskScoreClass.max
            )
        ) {
            Timber.tag(TAG)
                .v("$riskScore is above the defined min value ${highRiskScoreClass.min}")
            return true
        } else if (riskScore > highRiskScoreClass.max) {
            throw RiskLevelCalculationException(
                IllegalStateException("Risk score is above the max threshold for score class")
            )
        }

        return false
    }

    override fun isActiveTracingTimeAboveThreshold(): Boolean {
        val durationTracingIsActive = TimeVariables.getTimeActiveTracingDuration()
        val durationTracingIsActiveThreshold =
            TimeVariables.getMinActivatedTracingTime().toLong()

        val activeTracingDurationInHours = durationTracingIsActive.millisecondsToHours()

        return (activeTracingDurationInHours >= durationTracingIsActiveThreshold).also {
            Timber.tag(TAG).v(
                "Active tracing time ($activeTracingDurationInHours h) is above threshold " +
                    "($durationTracingIsActiveThreshold h): $it"
            )
            if (it) {
                Timber.tag(TAG).v("Active tracing time is not enough")
            }
        }
    }

    override fun calculateRiskScore(
        attenuationParameters: AttenuationDuration,
        exposureSummary: ExposureSummary
    ): Double {
        /** all attenuation values are capped to [TimeVariables.MAX_ATTENUATION_DURATION] */
        val weightedAttenuationLow =
            attenuationParameters.weights.low
                .times(exposureSummary.attenuationDurationsInMinutes[0].capped())
        val weightedAttenuationMid =
            attenuationParameters.weights.mid
                .times(exposureSummary.attenuationDurationsInMinutes[1].capped())
        val weightedAttenuationHigh =
            attenuationParameters.weights.high
                .times(exposureSummary.attenuationDurationsInMinutes[2].capped())

        val maximumRiskScore = exposureSummary.maximumRiskScore.toDouble()

        val defaultBucketOffset = attenuationParameters.defaultBucketOffset.toDouble()
        val normalizationDivisor = attenuationParameters.riskScoreNormalizationDivisor.toDouble()

        val attenuationStrings =
            "Weighted Attenuation: ($weightedAttenuationLow + $weightedAttenuationMid + " +
                "$weightedAttenuationHigh + $defaultBucketOffset)"
        Timber.v(attenuationStrings)

        val weightedAttenuationDuration =
            weightedAttenuationLow
                .plus(weightedAttenuationMid)
                .plus(weightedAttenuationHigh)
                .plus(defaultBucketOffset)

        Timber.v("Formula used: ($maximumRiskScore / $normalizationDivisor) * $weightedAttenuationDuration")

        val riskScore = (maximumRiskScore / normalizationDivisor) * weightedAttenuationDuration

        return round(riskScore.times(DECIMAL_MULTIPLIER)).div(DECIMAL_MULTIPLIER)
    }

    @VisibleForTesting
    internal fun Int.capped() =
        if (this > TimeVariables.getMaxAttenuationDuration()) {
            TimeVariables.getMaxAttenuationDuration()
        } else {
            this
        }

    @VisibleForTesting
    internal fun withinDefinedLevelThreshold(riskScore: Double, min: Int, max: Int) =
        riskScore >= min && riskScore <= max

    /**
     * Updates the Risk Level Score in the repository with the calculated Risk Level
     *
     * @param riskLevel
     */
    @VisibleForTesting
    internal fun updateRiskLevelScore(riskLevel: RiskLevel) {
        val lastCalculatedScore = RiskLevelRepository.getLastCalculatedScore()
        if (RiskLevel.riskLevelChangedBetweenLowAndHigh(
                lastCalculatedScore,
                riskLevel
            ) && !LocalData.submissionWasSuccessful()
        ) {
            NotificationHelper.sendNotification(
                CoronaWarnApplication.getAppContext().getString(R.string.notification_body),
                NotificationCompat.PRIORITY_HIGH
            )
        }
        RiskLevelRepository.setRiskLevelScore(riskLevel)
    }

    /**
     * If there is no persisted exposure summary we try to get a new one with the last persisted
     * Google API token that was used in the [de.rki.coronawarnapp.transaction.RetrieveDiagnosisKeysTransaction]
     *
     * @return a exposure summary from the Google Exposure Notification API
     */
    private suspend fun getNewExposureSummary(): ExposureSummary {
        val googleToken = LocalData.googleApiToken()
            ?: throw RiskLevelCalculationException(IllegalStateException("Exposure summary is not persisted"))

        val exposureSummary =
            InternalExposureNotificationClient.asyncGetExposureSummary(googleToken)

        return exposureSummary.also {
            Timber.tag(TAG)
                .v("Generated new exposure summary with $googleToken")
        }
    }

    companion object {
        private var TAG = DefaultRiskLevels::class.simpleName
        private const val DECIMAL_MULTIPLIER = 100
    }
}