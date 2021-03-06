/*
 * Copyright (C) 2012 Red Hat, Inc. (jcasey@redhat.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.maven.ext.manip.rest;

import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.ref.SimpleProjectVersionRef;
import org.commonjava.maven.ext.manip.rest.exception.RestException;
import org.commonjava.maven.ext.manip.rest.rule.MockServer;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.commonjava.maven.ext.manip.rest.DefaultVersionTranslator.RestProtocol;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpMessageVersionTranslatorTest
{
    private DefaultVersionTranslator versionTranslator;

    @Rule
    public TestName testName = new TestName();

    @ClassRule
    public static MockServer mockServer = new MockServer( new AbstractHandler()
    {
        @Override
        public void handle( String target, Request baseRequest, HttpServletRequest request,
                            HttpServletResponse response )
                        throws IOException, ServletException
        {
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
            baseRequest.setHandled( true );
            response.getWriter().println( "{\\\"message\\\":\\\"Communication with remote repository failed\\\"}");
        }
    } );

    @Before
    public void before()
    {
        LoggerFactory.getLogger( DefaultVersionTranslator.class ).info ("Executing test " + testName.getMethodName());

        this.versionTranslator = new DefaultVersionTranslator( mockServer.getUrl(), RestProtocol.DEPRECATED );
    }

    @Test
    public void testTranslateVersionsWithMessage()
    {
        List<ProjectVersionRef> gavs = new ArrayList<ProjectVersionRef>()
        {{
            add( new SimpleProjectVersionRef( "com.example", "example", "1.0" ) );
        }};

        try
        {
            versionTranslator.translateVersions( gavs );
            fail( "Failed to throw RestException when server failed to respond." );
        }
        catch ( RestException ex )
        {
            assertTrue( ex.getMessage().contains( "502" ) );
        }
    }
}
