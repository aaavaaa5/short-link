package com.app.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.config.algorithm.AlgorithmConfiguration;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.StandardShardingStrategyConfiguration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * ShardingSphere DataSource 手动配置
 */
@Configuration
public class ShardingSphereConfig {

    @Value("${spring.shardingsphere.datasource.ds0.jdbc-url}")
    private String ds0Url;

    @Value("${spring.shardingsphere.datasource.ds0.username}")
    private String ds0Username;

    @Value("${spring.shardingsphere.datasource.ds0.password}")
    private String ds0Password;

    @Value("${spring.shardingsphere.datasource.ds1.jdbc-url}")
    private String ds1Url;

    @Value("${spring.shardingsphere.datasource.ds1.username}")
    private String ds1Username;

    @Value("${spring.shardingsphere.datasource.ds1.password}")
    private String ds1Password;

    @Bean
    @Primary
    public DataSource dataSource() throws SQLException {
        Map<String, DataSource> dataSourceMap = new HashMap<>();
        dataSourceMap.put("ds0", createHikari(ds0Url, ds0Username, ds0Password));
        dataSourceMap.put("ds1", createHikari(ds1Url, ds1Username, ds1Password));

        ShardingRuleConfiguration shardingRule = new ShardingRuleConfiguration();

        ShardingTableRuleConfiguration linkTable = new ShardingTableRuleConfiguration("t_link",
                "ds$->{0..1}.t_link_$->{0..7}");
        linkTable.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration(
                "gid", "db-hash"));
        linkTable.setTableShardingStrategy(new StandardShardingStrategyConfiguration(
                "gid", "tbl-mod"));
        shardingRule.getTables().add(linkTable);

        // t_access_log 分片（分片键: link_gid，与 t_link 使用相同的分片算法）
        ShardingTableRuleConfiguration accessLogTable = new ShardingTableRuleConfiguration("t_access_log",
                "ds$->{0..1}.t_access_log_$->{0..7}");
        accessLogTable.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration(
                "link_gid", "db-hash"));
        accessLogTable.setTableShardingStrategy(new StandardShardingStrategyConfiguration(
                "link_gid", "tbl-mod"));
        shardingRule.getTables().add(accessLogTable);

        // t_access_stats 分片（分片键: link_gid，与 t_link 使用相同的分片算法）
        ShardingTableRuleConfiguration accessStatsTable = new ShardingTableRuleConfiguration("t_access_stats",
                "ds$->{0..1}.t_access_stats_$->{0..7}");
        accessStatsTable.setDatabaseShardingStrategy(new StandardShardingStrategyConfiguration(
                "link_gid", "db-hash"));
        accessStatsTable.setTableShardingStrategy(new StandardShardingStrategyConfiguration(
                "link_gid", "tbl-mod"));
        shardingRule.getTables().add(accessStatsTable);

        Properties dbHashProps = new Properties();
        dbHashProps.setProperty("sharding-count", "2");
        shardingRule.getShardingAlgorithms().put("db-hash",
                new AlgorithmConfiguration("HASH_MOD", dbHashProps));

        Properties tblModProps = new Properties();
        tblModProps.setProperty("sharding-count", "8");
        shardingRule.getShardingAlgorithms().put("tbl-mod",
                new AlgorithmConfiguration("HASH_MOD", tblModProps));

        Properties props = new Properties();
        props.setProperty("sql-show", "true");

        return ShardingSphereDataSourceFactory.createDataSource(dataSourceMap,
                java.util.Collections.singleton(shardingRule), props);
    }

    private HikariDataSource createHikari(String url, String username, String password) {
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        return ds;
    }
}
