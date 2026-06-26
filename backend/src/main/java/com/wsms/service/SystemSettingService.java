package com.wsms.service;

import com.wsms.entity.SystemSetting;
import com.wsms.repository.SystemSettingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SystemSettingService {

    private final SystemSettingRepository repository;

    @Autowired
    public SystemSettingService(SystemSettingRepository repository) {
        this.repository = repository;
    }

    public List<SystemSetting> getAllSettings() {
        return repository.findAll();
    }

    public SystemSetting getSetting(String key) {
        return repository.findById(key).orElse(null);
    }

    public String getSettingValue(String key, String defaultValue) {
        SystemSetting setting = getSetting(key);
        return setting != null ? setting.getSettingValue() : defaultValue;
    }

    public int getSettingValueAsInt(String key, int defaultValue) {
        try {
            String value = getSettingValue(key, null);
            return value != null ? Integer.parseInt(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public double getSettingValueAsDouble(String key, double defaultValue) {
        try {
            String value = getSettingValue(key, null);
            return value != null ? Double.parseDouble(value) : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    @Transactional
    public SystemSetting updateSetting(String key, String value) {
        SystemSetting setting = repository.findById(key)
                .orElse(new SystemSetting(key, value, "Custom setting"));
        setting.setSettingValue(value);
        return repository.save(setting);
    }
}
