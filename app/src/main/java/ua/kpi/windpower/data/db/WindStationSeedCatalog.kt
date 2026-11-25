package ua.kpi.windpower.data.db

import ua.kpi.windpower.data.db.entities.WindStation
import kotlin.math.roundToInt

/**
 * Початковий запис реальної вітрової електростанції України з початковою телеметрією
 * для наповнення локальної бази даних Room при першому розгортанні.
 */
data class SeedWindStationSpec(
    val station: WindStation,
    val initialPowersMw: List<Double>,
    val initialSpeedsMps: List<Double>,
    val initialVoltagesV: List<Double>
)

/**
 * Єдиний довідник реальних українських ВЕС і допоміжних координат регіонів.
 */
object WindStationSeedCatalog {

    val entries: List<SeedWindStationSpec> = listOf(
        SeedWindStationSpec(
            station = WindStation(
                id = 1,
                name = "Тилігульська ВЕС",
                location = "Миколаївська обл.",
                installedCapacityMw = 114.0,
                turbinesCount = 19,
                plantType = "Прибережна ВЕС",
                status = "Активна",
                latitude = 46.8420,
                longitude = 31.1150,
                description = "Турбіни Vestas EnVentus V162 (6.0 МВт), прибережний вітровий коридор Чорного моря."
            ),
            initialPowersMw = listOf(96.8, 84.2, 108.5),
            initialSpeedsMps = listOf(11.4, 10.6, 12.1),
            initialVoltagesV = listOf(690.0, 690.0, 691.5)
        ),
        SeedWindStationSpec(
            station = WindStation(
                id = 2,
                name = "Ботієвська ВЕС",
                location = "Запорізька обл.",
                installedCapacityMw = 200.0,
                turbinesCount = 65,
                plantType = "Прибережна ВЕС",
                status = "Пікове навантаження",
                latitude = 46.6790,
                longitude = 35.8440,
                description = "Високоефективний азовський вузол із турбінами Vestas V112 (3.075 МВт)."
            ),
            initialPowersMw = listOf(194.2, 182.0, 198.5),
            initialSpeedsMps = listOf(13.8, 12.9, 14.2),
            initialVoltagesV = listOf(690.0, 692.0, 690.0)
        ),
        SeedWindStationSpec(
            station = WindStation(
                id = 3,
                name = "Приморська ВЕС",
                location = "Запорізька обл.",
                installedCapacityMw = 200.0,
                turbinesCount = 52,
                plantType = "Прибережна ВЕС",
                status = "Активна",
                latitude = 46.7180,
                longitude = 36.3420,
                description = "Двочерговий вітропарк GE 3.8 МВт на узбережжі Азовського моря з балансуванням SCADA."
            ),
            initialPowersMw = listOf(158.4, 146.0, 171.2),
            initialSpeedsMps = listOf(10.6, 9.8, 11.2),
            initialVoltagesV = listOf(690.0, 689.5, 690.5)
        ),
        SeedWindStationSpec(
            station = WindStation(
                id = 4,
                name = "Сиваська ВЕС",
                location = "Херсонська обл.",
                installedCapacityMw = 245.0,
                turbinesCount = 64,
                plantType = "Гібрид ВЕС+АКБ",
                status = "Пікове навантаження",
                latitude = 46.2380,
                longitude = 33.8610,
                description = "Високопотужний комплекс із буферними системами накопичення (АКБ 40 МВт·год)."
            ),
            initialPowersMw = listOf(231.5, 218.0, 239.0),
            initialSpeedsMps = listOf(14.2, 13.4, 14.8),
            initialVoltagesV = listOf(690.0, 691.0, 690.0)
        ),
        SeedWindStationSpec(
            station = WindStation(
                id = 5,
                name = "Овід Вінд",
                location = "Одеська обл.",
                installedCapacityMw = 32.4,
                turbinesCount = 9,
                plantType = "Наземна ВЕС",
                status = "Активна",
                latitude = 46.2650,
                longitude = 30.4510,
                description = "Агрегати GE 3.6 МВт Овідіопольського енерговузла, режим MPPT."
            ),
            initialPowersMw = listOf(22.9, 19.8, 25.4),
            initialSpeedsMps = listOf(8.9, 8.2, 9.5),
            initialVoltagesV = listOf(690.0, 688.5, 690.0)
        ),
        SeedWindStationSpec(
            station = WindStation(
                id = 6,
                name = "Старий Самбір-1",
                location = "Львівська обл.",
                installedCapacityMw = 13.2,
                turbinesCount = 4,
                plantType = "Наземна ВЕС",
                status = "Активна",
                latitude = 49.4420,
                longitude = 23.0030,
                description = "Карпатський вітропарк передгірного профілю (Vestas V112-3.3 МВт)."
            ),
            initialPowersMw = listOf(9.4, 8.6, 10.2),
            initialSpeedsMps = listOf(9.3, 8.7, 9.9),
            initialVoltagesV = listOf(690.0, 690.0, 691.0)
        ),
        SeedWindStationSpec(
            station = WindStation(
                id = 7,
                name = "Орлівська ВЕС",
                location = "Запорізька обл.",
                installedCapacityMw = 100.0,
                turbinesCount = 26,
                plantType = "Наземна ВЕС",
                status = "Обслуговування",
                latitude = 46.7050,
                longitude = 36.0210,
                description = "Планове регламентне обслуговування інверторних підстанцій 110 кВ (режим обмеження)."
            ),
            initialPowersMw = listOf(18.5, 16.0, 21.0),
            initialSpeedsMps = listOf(6.4, 5.9, 6.8),
            initialVoltagesV = listOf(685.0, 686.5, 685.5)
        ),
        SeedWindStationSpec(
            station = WindStation(
                id = 8,
                name = "Дністровська ВЕС",
                location = "Одеська обл.",
                installedCapacityMw = 40.0,
                turbinesCount = 10,
                plantType = "Гібрид ВЕС+АКБ",
                status = "Активна",
                latitude = 46.1540,
                longitude = 30.0880,
                description = "Дельта Дністра: стабілізація добового графіка з компенсацією реактивної потужності."
            ),
            initialPowersMw = listOf(31.6, 28.4, 34.0),
            initialSpeedsMps = listOf(10.1, 9.4, 10.6),
            initialVoltagesV = listOf(690.0, 690.5, 690.0)
        ),
        SeedWindStationSpec(
            station = WindStation(
                id = 9,
                name = "Сколівська ВЕС",
                location = "Львівська обл.",
                installedCapacityMw = 55.0,
                turbinesCount = 10,
                plantType = "Наземна ВЕС",
                status = "Активна",
                latitude = 49.0380,
                longitude = 23.5120,
                description = "Гірський вітровий вузол 5.5 МВт з протиожеледним обігрівом лопатей."
            ),
            initialPowersMw = listOf(42.1, 38.7, 46.0),
            initialSpeedsMps = listOf(10.8, 10.1, 11.3),
            initialVoltagesV = listOf(690.0, 690.0, 689.5)
        )
    )

    /**
     * Формує стартові виміри телеметрії для новоствореної станції з потужністю [capacityMw].
     */
    fun defaultTelemetryFor(capacityMw: Double): Triple<List<Double>, List<Double>, List<Double>> {
        val safeCap = capacityMw.coerceAtLeast(1.0)
        val p1 = ((safeCap * 0.72) * 10.0).roundToInt() / 10.0
        val p2 = ((safeCap * 0.84) * 10.0).roundToInt() / 10.0
        val powers = listOf(p1, p2)
        val speeds = listOf(9.6, 10.8)
        val voltages = listOf(690.0, 690.5)
        return Triple(powers, speeds, voltages)
    }

    /**
     * Повертає наближені координати області України за назвою локації,
     * якщо користувач створює нову ВЕС без явного задання широти/довготи.
     */
    fun approximateRegionCoordinates(location: String): Pair<Double, Double> {
        val text = location.lowercase()
        return when {
            "миколаїв" in text -> 46.9750 to 31.9946
            "одес" in text -> 46.4825 to 30.7233
            "запор" in text -> 47.8388 to 35.1396
            "херсон" in text -> 46.6354 to 32.6169
            "львів" in text -> 49.8397 to 24.0297
            "івано-франк" in text || "прикарпат" in text -> 48.9226 to 24.7111
            "закарпат" in text -> 48.6208 to 22.2879
            "дніпр" in text -> 48.4647 to 35.0462
            "полтав" in text -> 49.5883 to 34.5514
            "черніг" in text -> 51.4982 to 31.2893
            "харків" in text -> 49.9935 to 36.2304
            "київ" in text -> 50.4501 to 30.5234
            "вінниц" in text -> 49.2331 to 28.4682
            else -> 48.4500 to 31.5000
        }
    }
}
