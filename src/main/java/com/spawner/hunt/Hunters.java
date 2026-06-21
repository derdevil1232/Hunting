package com.spawner.hunt;

import com.spawner.hunt.modules.SpawnerHunt;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Modules;

public class Hunters extends MeteorAddon {

    @Override
    public void onInitialize() {
        // Register your module here
        Modules.get().add(new SpawnerHunt());
    }

    @Override
    public String getPackage() {
        // FIXED: Changed from "com.example.addon" to match your actual package definition
        return "com.spawner.hunt";
    }
}
