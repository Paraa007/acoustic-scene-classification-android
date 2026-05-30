package com.fzi.acousticscene.model

/**
 * Static registry of training/evaluation info for known model files.
 * Keyed by the `.pt` file name. Returns `null` for models without recorded info.
 */
object ModelInfoRegistry {

    private val byFileName: Map<String, ModelInfo> = mapOf(
        "dcase2025_10s_04_29_32bt.pt" to ModelInfo(
            fileName = "dcase2025_10s_04_29_32bt.pt",
            runId = 87,
            batchSize = 32,
            learningRate = 0.003,
            weightDecay = 0.0002,
            dropout = 0.3,
            labelSmoothing = 0.1,
            valAccuracy = 94.44444444444444,
            testAccuracy = 92.94755877034359,
            testBalancedAccuracy = 93.31538653139427,
            testMacroF1 = 92.88609650209972,
            testWeightedF1 = 92.98162715436611,
            testMacroPrecision = 92.66999699388278,
            testMacroRecall = 93.31538653139427,
            testWeightedPrecision = 93.1734972677921,
            testWeightedRecall = 92.94755877034359,
            valTestDiff = -1.4968856741008523,
            bestEpoch = 86,
            totalEpochs = 100,
            trainValGap = -2.097800925925924,
            perClassF1 = listOf(
                93.12977099236642,  // 0 Transit / Vehicles
                91.6030534351145,   // 1 Urban / Waiting
                97.70992366412213,  // 2 Nature
                92.56198347107438,  // 3 Social
                96.12403100775194,  // 4 Work
                85.03937007874016,  // 5 Commercial
                96.06299212598425,  // 6 Leisure / Sport
                91.85185185185185,  // 7 Culture / Quiet
                91.8918918918919    // 8 Living Room
            ),
            augmentations = listOf("Freq-MixStyle", "SpecAugment", "Mixup")
        ),
        "dcase2025_10s_04_28_64bt.pt" to ModelInfo(
            fileName = "dcase2025_10s_04_28_64bt.pt",
            runId = 76,
            batchSize = 64,
            learningRate = 0.003,
            weightDecay = 0.0001,
            dropout = 0.2,
            labelSmoothing = 0.2,
            valAccuracy = 92.28395061728395,
            testAccuracy = 92.2242314647378,
            testBalancedAccuracy = 92.36371733318055,
            testMacroF1 = 92.35078142893471,
            testWeightedF1 = 92.21075737265197,
            testMacroPrecision = 92.8416074867639,
            testMacroRecall = 92.36371733318055,
            testWeightedPrecision = 92.71309235056086,
            testWeightedRecall = 92.2242314647378,
            valTestDiff = -0.05971915254615112,
            bestEpoch = 80,
            totalEpochs = 95,
            trainValGap = -2.6157407407407476,
            perClassF1 = listOf(
                91.72932330827066,  // 0 Transit / Vehicles
                91.04477611940298,  // 1 Urban / Waiting
                99.24812030075188,  // 2 Nature
                88.07339449541286,  // 3 Social
                96.92307692307692,  // 4 Work
                86.4,               // 5 Commercial
                93.65079365079364,  // 6 Leisure / Sport
                88.43537414965986,  // 7 Culture / Quiet
                95.65217391304348   // 8 Living Room
            ),
            augmentations = listOf("Freq-MixStyle", "SpecAugment", "Mixup")
        ),
        "dcase2025_10s_04_19_94bt.pt" to ModelInfo(
            fileName = "dcase2025_10s_04_19_94bt.pt",
            runId = 93,
            batchSize = 94,
            learningRate = 0.003,
            weightDecay = 0.0005,
            dropout = 0.25,
            labelSmoothing = 0.1,
            valAccuracy = 89.50617283950618,
            testAccuracy = 90.77757685352623,
            testBalancedAccuracy = 91.10714707628883,
            testMacroF1 = 90.75681330381121,
            testWeightedF1 = 90.81597730588047,
            testMacroPrecision = 90.83733365736254,
            testMacroRecall = 91.10714707628883,
            testWeightedPrecision = 91.25809138313501,
            testWeightedRecall = 90.77757685352623,
            valTestDiff = 1.2714040140200495,
            bestEpoch = 89,
            totalEpochs = 100,
            trainValGap = -12.538580246913583,
            perClassF1 = listOf(
                90.625,             // 0 Transit / Vehicles
                85.71428571428571,  // 1 Urban / Waiting
                98.50746268656717,  // 2 Nature
                89.83050847457628,  // 3 Social
                92.3076923076923,   // 4 Work
                88.52459016393442,  // 5 Commercial
                91.33858267716536,  // 6 Leisure / Sport
                89.55223880597015,  // 7 Culture / Quiet
                90.41095890410958   // 8 Living Room
            )
        ),
        "dcase2025_10s_04_06_64bt.pt" to ModelInfo(
            fileName = "dcase2025_10s_04_06_64bt.pt",
            runId = 141,
            batchSize = 64,
            learningRate = 0.001,
            weightDecay = 0.0005,
            dropout = 0.2,
            labelSmoothing = 0.2,
            valAccuracy = 93.51851851851852,
            testAccuracy = 93.85171790235081,
            testBalancedAccuracy = 93.99703240911855,
            testMacroF1 = 94.14920430257507,
            testWeightedF1 = 93.91219621099499,
            testMacroPrecision = 94.39624816268118,
            testMacroRecall = 93.99703240911855,
            testWeightedPrecision = 94.06851157316014,
            testWeightedRecall = 93.85171790235081,
            valTestDiff = 0.3331993838322944,
            bestEpoch = 73,
            totalEpochs = 88,
            trainValGap = 4.361915217349434,
            perClassF1 = listOf(
                93.33333333333333,  // 0 Transit / Vehicles
                87.87878787878788,  // 1 Urban / Waiting
                97.70992366412213,  // 2 Nature
                94.91525423728814,  // 3 Social
                96.92307692307692,  // 4 Work
                91.2,               // 5 Commercial
                96.875,             // 6 Leisure / Sport
                90.0,               // 7 Culture / Quiet
                98.50746268656717   // 8 Living Room
            )
        ),
        // Grid Search 05-16 — best model per batch size (10 s, CP-Mobile).
        "dcase2025_10s_05_16_8bt.pt" to ModelInfo(
            fileName = "dcase2025_10s_05_16_8bt.pt",
            runId = 26,
            batchSize = 8,
            learningRate = 0.002,
            weightDecay = 0.0001,
            dropout = 0.15,
            labelSmoothing = 0.2,
            valAccuracy = 95.71,
            testAccuracy = 92.79,
            testBalancedAccuracy = 92.48,
            testMacroF1 = 92.59,
            testWeightedF1 = 92.75,
            testMacroPrecision = 92.85,
            testMacroRecall = 92.48,
            // testWeightedPrecision not in source report
            testWeightedRecall = 92.79,
            valTestDiff = -2.92,
            bestEpoch = 84,
            totalEpochs = 99,
            trainValGap = -16.72,
            perClassF1 = listOf(
                94.10,  // 0 Transit / Vehicles
                89.40,  // 1 Urban / Waiting
                94.90,  // 2 Nature
                87.00,  // 3 Social
                96.30,  // 4 Work
                88.50,  // 5 Commercial
                100.00, // 6 Leisure / Sport
                92.30,  // 7 Culture / Quiet
                90.90   // 8 Living Room
            ),
            augmentations = listOf("Freq-MixStyle", "SpecAugment", "Mixup")
        ),
        "dcase2025_10s_05_16_16bt.pt" to ModelInfo(
            fileName = "dcase2025_10s_05_16_16bt.pt",
            runId = 97,
            batchSize = 16,
            learningRate = 0.004,
            weightDecay = 0.0001,
            dropout = 0.15,
            labelSmoothing = 0.1,
            valAccuracy = 96.61,
            testAccuracy = 93.24,
            testBalancedAccuracy = 92.95,
            testMacroF1 = 93.17,
            testWeightedF1 = 93.25,
            testMacroPrecision = 93.61,
            testMacroRecall = 92.95,
            // testWeightedPrecision not in source report
            testWeightedRecall = 93.24,
            valTestDiff = -3.37,
            bestEpoch = 77,
            totalEpochs = 92,
            trainValGap = -11.39,
            perClassF1 = listOf(
                92.00,  // 0 Transit / Vehicles
                87.50,  // 1 Urban / Waiting
                94.90,  // 2 Nature
                89.40,  // 3 Social
                100.00, // 4 Work
                86.80,  // 5 Commercial
                98.00,  // 6 Leisure / Sport
                96.20,  // 7 Culture / Quiet
                93.80   // 8 Living Room
            ),
            augmentations = listOf("Freq-MixStyle", "SpecAugment", "Mixup")
        ),
        "dcase2025_10s_05_16_32bt.pt" to ModelInfo(
            fileName = "dcase2025_10s_05_16_32bt.pt",
            runId = 101,
            batchSize = 32,
            learningRate = 0.004,
            weightDecay = 0.0001,
            dropout = 0.25,
            labelSmoothing = 0.1,
            valAccuracy = 95.71,
            testAccuracy = 92.79,
            testBalancedAccuracy = 92.41,
            testMacroF1 = 92.41,
            testWeightedF1 = 92.76,
            testMacroPrecision = 92.49,
            testMacroRecall = 92.41,
            // testWeightedPrecision not in source report
            testWeightedRecall = 92.79,
            valTestDiff = -2.92,
            bestEpoch = 88,
            totalEpochs = 100,
            trainValGap = -6.11,
            perClassF1 = listOf(
                94.10,  // 0 Transit / Vehicles
                85.70,  // 1 Urban / Waiting
                96.60,  // 2 Nature
                84.40,  // 3 Social
                98.10,  // 4 Work
                90.20,  // 5 Commercial
                100.00, // 6 Leisure / Sport
                94.30,  // 7 Culture / Quiet
                88.20   // 8 Living Room
            ),
            augmentations = listOf("Freq-MixStyle", "SpecAugment", "Mixup")
        ),
        "dcase2025_10s_05_16_64bt.pt" to ModelInfo(
            fileName = "dcase2025_10s_05_16_64bt.pt",
            runId = 113,
            batchSize = 64,
            learningRate = 0.004,
            weightDecay = 0.0005,
            dropout = 0.15,
            labelSmoothing = 0.1,
            valAccuracy = 94.36,
            testAccuracy = 93.69,
            testBalancedAccuracy = 93.27,
            testMacroF1 = 93.49,
            testWeightedF1 = 93.65,
            testMacroPrecision = 93.92,
            testMacroRecall = 93.27,
            // testWeightedPrecision not in source report
            testWeightedRecall = 93.69,
            valTestDiff = -0.67,
            bestEpoch = 86,
            totalEpochs = 100,
            trainValGap = -12.90,
            perClassF1 = listOf(
                94.10,  // 0 Transit / Vehicles
                89.40,  // 1 Urban / Waiting
                96.60,  // 2 Nature
                84.40,  // 3 Social
                96.30,  // 4 Work
                92.60,  // 5 Commercial
                100.00, // 6 Leisure / Sport
                94.30,  // 7 Culture / Quiet
                93.80   // 8 Living Room
            ),
            augmentations = listOf("Freq-MixStyle", "SpecAugment", "Mixup")
        ),
        "dcase2025_10s_05_16_128bt.pt" to ModelInfo(
            fileName = "dcase2025_10s_05_16_128bt.pt",
            runId = 139,
            batchSize = 128,
            learningRate = 0.005,
            weightDecay = 0.0005,
            dropout = 0.20,
            labelSmoothing = 0.1,
            valAccuracy = 91.87,
            testAccuracy = 90.99,
            testBalancedAccuracy = 90.79,
            testMacroF1 = 90.73,
            testWeightedF1 = 90.95,
            testMacroPrecision = 90.94,
            testMacroRecall = 90.79,
            // testWeightedPrecision not in source report
            testWeightedRecall = 90.99,
            valTestDiff = -0.88,
            bestEpoch = 96,
            totalEpochs = 100,
            trainValGap = 3.49,
            perClassF1 = listOf(
                87.50,  // 0 Transit / Vehicles
                87.50,  // 1 Urban / Waiting
                93.10,  // 2 Nature
                85.10,  // 3 Social
                94.50,  // 4 Work
                92.30,  // 5 Commercial
                96.00,  // 6 Leisure / Sport
                92.30,  // 7 Culture / Quiet
                88.20   // 8 Living Room
            ),
            augmentations = listOf("Freq-MixStyle", "SpecAugment", "Mixup")
        ),
        // 1 s model (CP-Mobile, 04-06 grid search run 129). 1 s clips drive the
        // FAST + AVERAGE methods. Augmentations not recorded for this run.
        "dcase2025_1s_04_06_128bt.pt" to ModelInfo(
            fileName = "dcase2025_1s_04_06_128bt.pt",
            runId = 129,
            batchSize = 128,
            learningRate = 0.001,
            weightDecay = 0.0002,
            dropout = 0.2,
            labelSmoothing = 0.2,
            valAccuracy = 94.65761511216057,
            testAccuracy = 88.19617622610141,
            testBalancedAccuracy = 88.47546262852426,
            testMacroF1 = 88.60868687110961,
            testWeightedF1 = 88.1750117800212,
            testMacroPrecision = 89.12085389547222,
            testMacroRecall = 88.47546262852426,
            testWeightedPrecision = 88.53223052223885,
            testWeightedRecall = 88.19617622610141,
            valTestDiff = -6.461438886059156,
            bestEpoch = 51,
            totalEpochs = 66,
            trainValGap = 81.47651683128402,
            perClassF1 = listOf(
                88.56382978723404,  // 0 Transit / Vehicles
                81.602172437203,    // 1 Urban / Waiting
                93.45670852610706,  // 2 Nature
                83.46055979643766,  // 3 Social
                94.16725228390725,  // 4 Work
                79.85507246376812,  // 5 Commercial
                90.08498583569406,  // 6 Leisure / Sport
                88.82870683818551,  // 7 Culture / Quiet
                97.45889387144993   // 8 Living Room
            )
        )
    )

    fun get(fileName: String): ModelInfo? = byFileName[fileName]

    fun has(fileName: String): Boolean = byFileName.containsKey(fileName)

    /** File name of the registered model with the highest test accuracy, or null if empty. */
    fun bestTestAccuracyModel(): String? =
        byFileName.values.maxByOrNull { it.testAccuracy }?.fileName
}
