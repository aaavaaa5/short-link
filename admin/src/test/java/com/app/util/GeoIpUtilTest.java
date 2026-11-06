package com.app.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeoIpUtilTest {

    @Test
    void parsesIp2RegionV4ProvinceAndCityFields() {
        GeoIpUtil geoIpUtil = new GeoIpUtil();
        geoIpUtil.init();

        assertEquals("中国", geoIpUtil.parseCountry("114.114.114.114"));
        assertEquals("江苏省", geoIpUtil.parseProvince("114.114.114.114"));
        assertEquals("南京市", geoIpUtil.parseCity("114.114.114.114"));

        assertEquals("United States", geoIpUtil.parseCountry("8.8.8.8"));
        assertEquals("California", geoIpUtil.parseProvince("8.8.8.8"));
        assertEquals("未知", geoIpUtil.parseCity("8.8.8.8"));
    }
}
