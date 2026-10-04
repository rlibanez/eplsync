package com.rlibanez.eplsync.settings;

import jakarta.persistence.*;

@Entity
@Table(name = "app_settings")
public class StoredSetting {
    @Id @Column(name = "setting_key") private String key;
    @Column(name = "setting_value", nullable = false, columnDefinition = "TEXT") private String value;
}
