/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.service.tree;

import static org.junit.Assert.assertNotNull;

import java.util.Properties;

import com.akiban.server.service.config.TestConfigService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.akiban.server.service.Service;
import com.akiban.server.service.config.ConfigurationService;

public class TreeServiceImplTest {

    private final static int MEGA = 1024 * 1024;

    private static class MyConfigService extends TestConfigService {
    }

    private MyConfigService configService;

    @Before
    public void startConfiguration() throws Exception {
        configService = new MyConfigService();
        configService.start();
    }

    @After
    public void stopConfiguration() throws Exception {
        configService.start();
    }

    @Test
    public void startupPropertiesTest() throws Exception {
        final Properties properties = TreeServiceImpl.setupPersistitProperties(configService);
        assertNotNull(properties.getProperty("datapath"));
        assertNotNull(properties.getProperty("buffer.memory.16384"));
    }
    
}
