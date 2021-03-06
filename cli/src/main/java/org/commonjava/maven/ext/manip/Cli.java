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
package org.commonjava.maven.ext.manip;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.io.FileUtils;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.MavenArtifactRepository;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulationException;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.InputLocation;
import org.apache.maven.model.building.ModelProblem;
import org.apache.maven.model.building.ModelProblemCollector;
import org.apache.maven.model.profile.DefaultProfileActivationContext;
import org.apache.maven.model.profile.ProfileActivationContext;
import org.apache.maven.model.profile.ProfileSelector;
import org.apache.maven.settings.Profile;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.SettingsUtils;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.ext.manip.impl.RESTManipulator;
import org.commonjava.maven.ext.manip.io.ConfigIO;
import org.commonjava.maven.ext.manip.io.PomIO;
import org.commonjava.maven.ext.manip.io.XMLIO;
import org.commonjava.maven.ext.manip.model.SimpleScopedArtifactRef;
import org.commonjava.maven.ext.manip.rest.exception.RestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.apache.commons.lang.StringUtils.isNotEmpty;

public class Cli
{
    private static final File DEFAULT_GLOBAL_SETTINGS_FILE =
        new File( System.getProperty( "maven.home", System.getProperty( "user.dir", "" ) ), "conf/settings.xml" );

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private ManipulationSession session;

    private ManipulationManager manipulationManager;

    private PomIO pomIO;

    /**
     * Default pom file to operate against.
     */
    private File target = new File( System.getProperty( "user.dir" ), "pom.xml" );

    /**
     * Optional settings.xml file.
     */
    private File settings = null;

    /**
     * Properties a user may define on the command line.
     */
    private Properties userProps;

    public static void main( String[] args )
    {
        System.exit ( new Cli().run( args ) );
    }

    public int run( String[] args )
    {
        Options options = new Options();
        options.addOption( "h", false, "Print this help message." );
        options.addOption( Option.builder( "d" ).longOpt( "debug" ).desc( "Enable debug" ).build() );
        options.addOption( Option.builder( "t" ).longOpt( "debug" ).desc( "Enable trace" ).build() );
        options.addOption( Option.builder( "h" ).longOpt( "help" ).desc( "Print help" ).build() );
        options.addOption( Option.builder( "f" )
                                 .longOpt( "file" )
                                 .hasArgs()
                                 .numberOfArgs( 1 )
                                 .desc( "POM file" )
                                 .build() );
        options.addOption( Option.builder()
                                 .longOpt( "log-context" )
                                 .desc( "Add log-context ID" )
                                 .numberOfArgs( 1 )
                                 .build() );
        options.addOption( Option.builder( "l" )
                                 .longOpt( "log" )
                                 .desc( "Log file to output logging to" )
                                 .numberOfArgs( 1 )
                                 .build() );
        options.addOption( Option.builder( "s" )
                                 .longOpt( "settings" )
                                 .hasArgs()
                                 .numberOfArgs( 1 )
                                 .desc( "Optional settings.xml file" )
                                 .build() );
        options.addOption( Option.builder( "P" )
                                 .longOpt( "activeProfiles" )
                                 .desc( "Comma separated list of active profiles. Only used with '-p' (Print all project dependencies)" )
                                 .numberOfArgs( 1 )
                                 .build() );
        options.addOption( Option.builder( "o" )
                                 .longOpt( "outputFile" )
                                 .desc( "outputFile to output dependencies to. Only used with '-p' (Print all project dependencies)" )
                                 .numberOfArgs( 1 )
                                 .build() );
        options.addOption( Option.builder( "p" ).longOpt( "printDeps" ).desc( "Print all project dependencies" ).build() );
        options.addOption( Option.builder()
                                 .longOpt( "printGAVTC" )
                                 .desc( "Print all project dependencies in group:artifact:version:type:classifier with scope information" )
                                 .build() );
        options.addOption( Option.builder()
                                 .longOpt( "printUnusedDepMgmt" )
                                 .desc( "Print unused managed dependencies in group:artifact:version format" )
                                 .build() );
        options.addOption( Option.builder( "D" )
                                 .hasArgs()
                                 .numberOfArgs( 2 )
                                 .valueSeparator( '=' )
                                 .desc( "Java Properties" )
                                 .build() );
        options.addOption( Option.builder( "x" )
                                 .hasArgs()
                                 .numberOfArgs( 2 )
                                 .desc( "XPath tester ( file : xpath )" )
                                 .build() );

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd;
        try
        {
            cmd = parser.parse( options, args );
        }
        catch ( ParseException e )
        {
            logger.debug( "Caught problem parsing ", e );
            System.err.println( e.getMessage() );

            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp( "...", options );
            return 10;
        }

        if ( cmd.hasOption( 'h' ) )
        {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp( "...", options );
            System.exit( 0 );
        }
        if ( cmd.hasOption( 'D' ) )
        {
            userProps = cmd.getOptionProperties( "D" );
        }
        if ( cmd.hasOption( 'f' ) )
        {
            target = new File( cmd.getOptionValue( 'f' ) );
        }
        if ( cmd.hasOption( 's' ) )
        {
            settings = new File( cmd.getOptionValue( 's' ) );
        }
        if ( cmd.hasOption( "log-context" ) )
        {
            String mdc = cmd.getOptionValue( "log-context" );
            if ( isNotEmpty( mdc ) )
            {
                // Append a space to split up level and log-context markers.
                MDC.put( "LOG-CONTEXT", mdc + ' ' );
            }
        }

        createSession( target, settings );

        final ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger( org.slf4j.Logger.ROOT_LOGGER_NAME );
        if ( cmd.hasOption( 'l' ) )
        {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            loggerContext.reset();

            PatternLayoutEncoder ple = new PatternLayoutEncoder();
            ple.setPattern("%mdc{LOG-CONTEXT}%level %logger{36} %msg%n");
            ple.setContext( loggerContext );
            ple.start();

            FileAppender<ILoggingEvent> fileAppender = new FileAppender<>();
            fileAppender.setEncoder( ple );
            fileAppender.setContext( loggerContext );
            fileAppender.setName( "fileLogging" );
            fileAppender.setAppend( false );
            fileAppender.setFile( cmd.getOptionValue( "l" ) );
            fileAppender.start();

            root.addAppender( fileAppender );
            root.setLevel( Level.INFO );
        }
        // Set debug logging after session creation else we get the log filled with Plexus
        // creation stuff.
        if ( cmd.hasOption( 'd' ) )
        {
            root.setLevel( Level.DEBUG );
        }
        if ( cmd.hasOption( 't' ) )
        {
            root.setLevel( Level.TRACE );
        }

        if ( !session.isEnabled() )
        {
            logger.info( "Manipulation engine disabled via command-line option" );
            return 10;
        }
        if ( !target.exists() )
        {
            logger.info( "Manipulation engine disabled. No project found." );
            return 10;
        }
        // Don't bother skipping if we're just trying to analyse deps.
        else if ( new File( target.getParentFile(), ManipulationManager.MARKER_FILE ).exists() && !cmd.hasOption( 'p' ) )
        {
            logger.info( "Skipping manipulation as previous execution found." );
            return 0;
        }
        try
        {
            Properties config = new ConfigIO().parse( target.getParentFile() );
            String value = session.getUserProperties().getProperty( "allowConfigFilePrecedence" );
            if ( isNotEmpty( value ) && "true".equalsIgnoreCase( value ) )
            {
                session.getUserProperties().putAll( config );
            }
            else
            {
                for ( String key : config.stringPropertyNames() )
                {
                    if ( ! session.getUserProperties().containsKey( key ) )
                    {
                        session.getUserProperties().setProperty( key, config.getProperty(key) );
                    }
                }
            }
        }
        catch ( ManipulationException e )
        {
            logger.error( "POM Manipulation failed: Unable to read config file ", e );
            return 10;
        }


        try
        {
            manipulationManager.init( session );

            if ( cmd.hasOption( 'x' ) )
            {
                String []params = cmd.getOptionValues( 'x' );
                if ( params.length != 2)
                {
                    throw new ManipulationException( "Invalid number of parameters (" + params.length + "); should be <file> <xpath>" );
                }
                XMLIO xmlIO = new XMLIO();

                Document doc = xmlIO.parseXML( new File ( params[0] ) );
                XPath xPath = XPathFactory.newInstance().newXPath();
                NodeList nodeList = (NodeList) xPath.evaluate( params[1], doc, XPathConstants.NODESET);
                logger.info ("Found {} node", nodeList.getLength());

                for ( int i = 0; i < nodeList.getLength(); i++)
                {
                    Node node = nodeList.item( i );
                    logger.info  ("Found node {} and value {} ", node.getNodeName(), node.getTextContent());
                }
            }
            else if ( cmd.hasOption( 'p' ) || cmd.hasOption( "printGAVTC" ) || cmd.hasOption( "printUnusedDepMgmt" ))
            {
                Set<String> activeProfiles = null;
                if ( cmd.hasOption( 'P' ) )
                {
                    activeProfiles = new HashSet<>();
                    Collections.addAll( activeProfiles, cmd.getOptionValue( 'P' ).split( "," ) );
                }
                Set<ArtifactRef> ts = RESTManipulator.establishAllDependencies( pomIO.parseProject( session.getPom() ), activeProfiles );
                logger.info( "Found {} dependencies.", ts.size() );
                File output = null;

                if ( cmd.hasOption( 'o' ) )
                {
                    output = new File( cmd.getOptionValue( 'o' ) );
                    output.delete();
                }
                if ( cmd.hasOption( "printUnusedDepMgmt" ) )
                {
                    Set<ArtifactRef> nonMangedDeps = RESTManipulator.establishNonManagedDependencies( pomIO.parseProject( session.getPom() ), activeProfiles );
                    logger.info( "Found {} non-managed dependencies.", nonMangedDeps.size() );
                    // As the managed dependencies may have versions versus the non-managed strip off the versions to see what is left.
                    Set<ProjectRef> tsNoV = new TreeSet<>(  );
                    for ( ArtifactRef ar : ts)
                    {
                        tsNoV.add( ar.asProjectRef() );
                    }
                    Set<ProjectRef> nonManagedNoV = new TreeSet<>(  );
                    for ( ArtifactRef ar : nonMangedDeps)
                    {
                        nonManagedNoV.add( ar.asProjectRef() );
                    }
                    tsNoV.removeAll( nonManagedNoV );

                    for ( ProjectRef pr : tsNoV)
                    {
                        if ( cmd.hasOption( 'o' ) )
                        {
                            FileUtils.writeStringToFile( output, pr.toString(), true );
                        }
                        else
                        {
                            System.out.println( pr.toString() );
                        }
                    }
                }
                else
                {
                    for ( ArtifactRef a : ts )
                    {
                        String scope = null;
                        if ( a instanceof SimpleScopedArtifactRef )
                        {
                            scope = ( (SimpleScopedArtifactRef) a ).getScope();
                        }
                        if ( cmd.hasOption( 'o' ) )
                        {
                            if ( cmd.hasOption( "printGAVTC" ) )
                            {
                                FileUtils.writeStringToFile( output, String.format( "%-80s%10s\n", a, scope ), true );
                            }
                            else
                            {
                                FileUtils.writeStringToFile( output, a.asProjectVersionRef().toString() + '\n', true );
                            }
                        }
                        else
                        {
                            if ( cmd.hasOption( "printGAVTC" ) )
                            {
                                System.out.format( "%-80s%10s\n", a, scope );
                            }
                            else
                            {
                                System.out.println( a.asProjectVersionRef() );
                            }
                        }
                    }
                }
            }
            else
            {
                manipulationManager.scanAndApply( session );
            }
        }
        catch ( ManipulationException e )
        {
            logger.error( "POM Manipulation failed; original error is {}", e.getMessage() );
            logger.debug( "POM Manipulation error trace is", e );
            return 10;
        }
        catch ( RestException e )
        {
            logger.error ( "REST communication with {} failed. {}", userProps.getProperty( "restURL" ), e.getMessage () );
            logger.trace ( "Exception trace is", e);
            return 100;
        }
        catch ( Exception e )
        {
            logger.error( "POM Manipulation failed.", e );
            return 100;
        }
        return 0;
    }

    private void createSession( File target, File settings )
    {
        try
        {
            PlexusContainer container = new DefaultPlexusContainer( );

            final MavenExecutionRequest req =
                new DefaultMavenExecutionRequest().setUserProperties( System.getProperties() )
                                                  .setUserProperties( userProps )
                                                  .setRemoteRepositories( Collections.<ArtifactRepository>emptyList() );

            if (userProps != null && userProps.containsKey( "maven.repo.local" ))
            {
                ArtifactRepository ar = new MavenArtifactRepository(  );
                ar.setUrl( "file://" + userProps.getProperty( "maven.repo.local" ));
                req.setLocalRepository( ar );
            }

            if ( settings != null )
            {
                req.setUserSettingsFile( settings );
                req.setGlobalSettingsFile( settings );

                MavenExecutionRequestPopulator executionRequestPopulator =
                    container.lookup( MavenExecutionRequestPopulator.class );
                executionRequestPopulator.populateFromSettings( req, parseSettings( settings ) );
            }

            final MavenSession mavenSession =
                new MavenSession( container, null, req, new DefaultMavenExecutionResult() );

            mavenSession.getRequest().setPom( target );

            pomIO = container.lookup( PomIO.class );
            session = container.lookup( ManipulationSession.class );
            manipulationManager = container.lookup( ManipulationManager.class );

            session.setMavenSession( mavenSession );
        }
        catch ( ComponentLookupException e )
        {
            logger.debug( "Caught problem instantiating ", e );
            System.err.println( "Unable to start Cli subsystem" );
            System.exit( 100 );
            e.printStackTrace();
        }
        catch ( PlexusContainerException e )
        {
            logger.debug( "Caught problem instantiating ", e );
            System.err.println( "Unable to start Cli subsystem" );
            System.exit( 100 );
        }
        catch ( SettingsBuildingException e )
        {
            logger.debug( "Caught problem parsing settings file ", e );
            System.err.println( "Unable to parse settings.xml file" );
            System.exit( 100 );
        }
        catch ( MavenExecutionRequestPopulationException e )
        {
            logger.debug( "Caught problem populating maven request from settings file ", e );
            System.err.println( "Unable to create maven execution request from settings.xml file" );
            System.exit( 100 );
        }
    }

    private Settings parseSettings( File settings )
        throws PlexusContainerException, ComponentLookupException, SettingsBuildingException
    {
        PlexusContainer container = new DefaultPlexusContainer();
        DefaultSettingsBuildingRequest settingsRequest = new DefaultSettingsBuildingRequest();
        settingsRequest.setUserSettingsFile( settings );
        settingsRequest.setGlobalSettingsFile( DEFAULT_GLOBAL_SETTINGS_FILE );

        SettingsBuilder settingsBuilder = container.lookup( SettingsBuilder.class );
        SettingsBuildingResult settingsResult = settingsBuilder.build( settingsRequest );
        Settings effectiveSettings = settingsResult.getEffectiveSettings();

        ProfileSelector profileSelector = container.lookup( ProfileSelector.class );
        ProfileActivationContext profileActivationContext =
            new DefaultProfileActivationContext().setActiveProfileIds( effectiveSettings.getActiveProfiles() );
        List<org.apache.maven.model.Profile> modelProfiles = new ArrayList<>();
        for ( Profile profile : effectiveSettings.getProfiles() )
        {
            modelProfiles.add( SettingsUtils.convertFromSettingsProfile( profile ) );
        }
        List<org.apache.maven.model.Profile> activeModelProfiles =
            profileSelector.getActiveProfiles( modelProfiles, profileActivationContext, new ModelProblemCollector()
            {

                @Override
                public void add( ModelProblem.Severity severity, String message, InputLocation location,
                                 Exception cause )
                {
                    // do nothing
                }
            } );
        List<String> activeProfiles = new ArrayList<>();
        for ( org.apache.maven.model.Profile profile : activeModelProfiles )
        {
            activeProfiles.add( profile.getId() );
        }
        effectiveSettings.setActiveProfiles( activeProfiles );

        return effectiveSettings;
    }
}
