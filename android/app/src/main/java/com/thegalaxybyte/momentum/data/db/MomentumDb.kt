package com.thegalaxybyte.momentum.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class, WeightEntity::class, FoodEntity::class, FoodLogEntity::class,
        LadderStateEntity::class, WorkoutSessionEntity::class, SetLogEntity::class, CardioEntity::class,
        DailyActivityEntity::class, SettingEntity::class, CoachReviewEntity::class, CoachMessageEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class MomentumDb : RoomDatabase() {
    abstract fun dao(): MomentumDao

    companion object {
        fun create(context: Context): MomentumDb =
            Room.databaseBuilder(context, MomentumDb::class.java, "momentum.db").build()
    }
}
