package com.thegalaxybyte.momentum.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProfileEntity::class, WeightEntity::class, FoodEntity::class, FoodLogEntity::class,
        LadderStateEntity::class, WorkoutSessionEntity::class, SetLogEntity::class, CardioEntity::class,
        DailyActivityEntity::class, SettingEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class MomentumDb : RoomDatabase() {
    abstract fun dao(): MomentumDao

    companion object {
        fun create(context: Context): MomentumDb =
            Room.databaseBuilder(context, MomentumDb::class.java, "momentum.db").build()
    }
}
