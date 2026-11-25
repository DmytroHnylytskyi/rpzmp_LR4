package ua.kpi.windpower.data.db.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Головна сутність вітроелектростанції (ВЕС) у Room БД (версія схеми 4).
 * Виступає батьківською таблицею у реляційній схемі та єдиним локальним джерелом
 * правди для екранів бази даних, прогнозів REST API й геопросторової карти.
 *
 * @property id Унікальний числовий ідентифікатор станції (автогенерація).
 * @property name Офіційна назва ВЕС (наприклад, «Тилігульська ВЕС»).
 * @property location Географічне розташування або область.
 * @property installedCapacityMw Встановлена електрична потужність вітропарку (МВт).
 * @property turbinesCount Кількість діючих вітроустановок (ВЕУ).
 * @property plantType Тип розміщення станції («Наземна ВЕС», «Прибережна ВЕС», «Гібрид ВЕС+АКБ»).
 * @property status Поточний диспетчерський статус («Активна», «Пікове навантаження», «Обслуговування»).
 * @property latitude Географічна широта майданчика ВЕС (WGS-84).
 * @property longitude Географічна довгота майданчика ВЕС (WGS-84).
 * @property description Короткий технічний опис об'єкта та складу обладнання.
 */
@Entity(tableName = "wind_stations")
data class WindStation(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val location: String = "Україна",
    val installedCapacityMw: Double = 0.0,
    @ColumnInfo(defaultValue = "10")
    val turbinesCount: Int = 10,
    @ColumnInfo(defaultValue = "Наземна ВЕС")
    val plantType: String = "Наземна ВЕС",
    @ColumnInfo(defaultValue = "Активна")
    val status: String = "Активна",
    @ColumnInfo(defaultValue = "48.45")
    val latitude: Double = 48.45,
    @ColumnInfo(defaultValue = "31.50")
    val longitude: Double = 31.50,
    @ColumnInfo(defaultValue = "")
    val description: String = ""
)
