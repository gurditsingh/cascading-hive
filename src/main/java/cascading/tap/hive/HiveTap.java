/*
* Copyright (c) 2007-2016 Concurrent, Inc. All Rights Reserved.
*
* Project and contact information: http://www.cascading.org/
*
* This file is part of the Cascading project.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package cascading.tap.hive;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

import cascading.CascadingException;
import cascading.flow.FlowProcess;
import cascading.flow.hadoop.util.HadoopUtil;
import cascading.property.AppProps;
import cascading.scheme.Scheme;
import cascading.tap.SinkMode;
import cascading.tap.TapException;
import cascading.tap.hadoop.Hfs;
import cascading.tuple.TupleEntryCollector;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.InvalidObjectException;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static cascading.tap.hive.HiveTableDescriptor.HIVE_ACID_TABLE_PARAMETER_KEY;

/**
 * HiveTap is Tap implementation, which can create Hive tables on HDFS. HiveTap supports a strict mode, which will
 * make sure that an existing table has the same structure as the one requested by the user. This behaviour is off by
 * default and can be enabled by passing strict=true to the constructor.
 */
public class HiveTap extends Hfs
  {
  /** Field LOG */
  private static final Logger LOG = LoggerFactory.getLogger( HiveTap.class );

  static
    {
    // add cascading-hive release to frameworks
    Properties properties = new Properties();
    InputStream stream = HiveTap.class.getClassLoader().getResourceAsStream( "cascading/framework.properties" );
    if( stream != null )
      {
      try
        {
        properties.load( stream );
        stream.close();
        }
      catch( IOException exception )
        {
        // ingore
        }
      }
    String framework = properties.getProperty( "name" );
    AppProps.addApplicationFramework( null, framework );
    }

  /** TableDescriptor for the table. */
  private final HiveTableDescriptor tableDescriptor;

  /** strict mode enforces that an existing table has to match the given TableDescriptor */
  private boolean strict;

  /** last modified time */
  private long modifiedTime;

  /** ACID property of this table */
  private boolean transactional;

  /** Provides IMetaStoreClient instances as needed. */
  private MetaStoreClientFactory metaStoreClientFactory;

  /**
   * Constructs a new HiveTap instance.
   *
   * @param tableDesc The HiveTableDescriptor for creating and validating Hive tables.
   * @param scheme    The Scheme to be used by the Tap.
   */
  public HiveTap( HiveTableDescriptor tableDesc, Scheme scheme )
    {
    this( tableDesc, scheme, SinkMode.KEEP, false );
    }

  /**
   * Constructs a new HiveTap instance.
   *
   * @param tableDesc The HiveTableDescriptor for creating and validating Hive tables.
   * @param scheme    The Scheme to be used by the Tap.
   * @param mode      The SinkMode to use
   * @param strict    Enables and disables strict validation of hive tables.
   */
  public HiveTap( HiveTableDescriptor tableDesc, Scheme scheme, SinkMode mode, boolean strict )
    {
    this( tableDesc, scheme, mode, strict, new MetaStoreClientFactory() );
    }

  HiveTap( HiveTableDescriptor tableDesc, Scheme scheme, SinkMode mode, boolean strict, MetaStoreClientFactory metaStoreClientFactory )
    {
    super( scheme, null, mode );
    this.tableDescriptor = tableDesc;
    this.strict = strict;
    this.metaStoreClientFactory = metaStoreClientFactory;
    setScheme( scheme );
    setFilesystemLocation();
    setTransactional();
    }

  @Override
  public boolean createResource( Configuration conf ) throws IOException
    {
    if( !resourceExists( conf ) )
      return createHiveTable( conf );
    return true;
    }

  /**
   * Private method to create Hive table in the MetaStore.
   *
   * @return true, if the table has been created successfully.
   * @throws IOException In case an interaction with the Hive metastore fails.
   */
  private boolean createHiveTable( Configuration configuration ) throws IOException
    {
    forbidIfTransactional();
    IMetaStoreClient metaStoreClient = null;
    try
      {
      metaStoreClient = metaStoreClientFactory.newInstance( configuration );
      Table hiveTable = tableDescriptor.toHiveTable();
      try
        {
        metaStoreClient.getDatabase( tableDescriptor.getDatabaseName() );
        }
      // there is no databaseExists method in hive 0.10, so we have to use exceptions for flow control.
      catch( NoSuchObjectException exception )
        {
        LOG.info( "creating database '{}' at '{}' ", tableDescriptor.getDatabaseName(), getPath().getParent().toString() );
        Database db = new Database( tableDescriptor.getDatabaseName(), "created by Cascading",
          getPath().getParent().toString(), null );
        metaStoreClient.createDatabase( db );
        }
      LOG.info( "creating table '{}' at '{}' ", tableDescriptor.getTableName(), getPath().toString() );

      metaStoreClient.createTable( hiveTable );
      modifiedTime = System.currentTimeMillis();
      return true;
      }
    catch( MetaException exception )
      {
      throw new IOException( exception );
      }
    catch( TException exception )
      {
      throw new IOException( exception );
      }
    finally
      {
      if( metaStoreClient != null )
        metaStoreClient.close();
      }
    }

  @Override
  public boolean resourceExists( Configuration conf ) throws IOException
    {
    IMetaStoreClient metaStoreClient = null;
    try
      {
      metaStoreClient = metaStoreClientFactory.newInstance( conf );
      Table table = metaStoreClient.getTable( tableDescriptor.getDatabaseName(),
        tableDescriptor.getTableName() );

      modifiedTime = table.getLastAccessTime();
      // check if the schema matches the table descriptor. If not, throw an exception.
      if( strict )
        {
        LOG.info( "strict mode: comparing existing hive table with table descriptor" );
        if( !table.getTableType().equals( tableDescriptor.toHiveTable().getTableType() ) )
          throw new HiveTableValidationException( String.format( "expected a table of type '%s' but found '%s'",
            tableDescriptor.toHiveTable().getTableType(), table.getTableType() ) );

        // Check that the paths are the same
        FileSystem fs = FileSystem.get( conf );
        StorageDescriptor sd = table.getSd();
        Path expectedPath = fs.makeQualified( new Path( tableDescriptor.getLocation( HiveConfFactory.getHiveConf( conf ).getVar( ConfVars.METASTOREWAREHOUSE ) ) ) );
        Path actualPath = fs.makeQualified( new Path( sd.getLocation() ) );

        if( !expectedPath.equals( actualPath ) )
          throw new HiveTableValidationException( String.format(
            "table in MetaStore does not have the same path. Expected %s got %s",
            expectedPath, actualPath ) );

        List<FieldSchema> schemaList = sd.getCols();
        if( schemaList.size() != tableDescriptor.getColumnNames().length - tableDescriptor.getPartitionKeys().length )
          throw new HiveTableValidationException( String.format(
            "table in MetaStore does not have same number of columns. expected %d got %d",
            tableDescriptor.getColumnNames().length - tableDescriptor.getPartitionKeys().length,
            schemaList.size() ) );

        if( tableDescriptor.isTransactional() != Boolean.parseBoolean( table.getParameters().get( HIVE_ACID_TABLE_PARAMETER_KEY ) ) )
          throw new HiveTableValidationException( String.format(
            "table in MetaStore does not have the same ACID properties. expected %b got %b",
            tableDescriptor.isTransactional(), Boolean.parseBoolean( table.getParameters().get( HIVE_ACID_TABLE_PARAMETER_KEY ) ) ) );

        for( int index = 0; index < schemaList.size(); index++ )
          {
          FieldSchema schema = schemaList.get( index );
          String expectedColumnName = tableDescriptor.getColumnNames()[ index ];
          String expectedColumnType = tableDescriptor.getColumnTypes()[ index ];
          // this could be extended to the StorageDescriptor if necessary.
          if( !schema.getName().equalsIgnoreCase( expectedColumnName ) )
            throw new HiveTableValidationException( String.format(
              "hive schema mismatch: expected column name '%s', but found '%s'", expectedColumnName, schema.getName() ) );
          if( !schema.getType().equalsIgnoreCase( expectedColumnType ) )
            throw new HiveTableValidationException( String.format(
              "hive schema mismatch: expected column type '%s', but found '%s'", expectedColumnType, schema.getType() ) );
          }
        List<FieldSchema> schemaPartitions = table.getPartitionKeys();
        if( schemaPartitions.size() != tableDescriptor.getPartitionKeys().length )
          throw new HiveTableValidationException( String.format(
            "table in MetaStore does not have same number of partition columns. expected %d got %d",
            tableDescriptor.getPartitionKeys().length,
            schemaPartitions.size() ) );
        int offset = tableDescriptor.getColumnNames().length - tableDescriptor.getPartitionKeys().length;
        for( int index = 0; index < schemaPartitions.size(); index++ )
          {
          FieldSchema schema = schemaPartitions.get( index );
          String expectedColumnName = tableDescriptor.getColumnNames()[ index + offset ];
          String expectedColumnType = tableDescriptor.getColumnTypes()[ index + offset ];
          // this could be extended to the StorageDescriptor if necessary.
          if( !schema.getName().equalsIgnoreCase( expectedColumnName ) )
            throw new HiveTableValidationException( String.format(
              "hive partition schema mismatch: expected column name '%s', but found '%s'", expectedColumnName, schema.getName() ) );
          if( !schema.getType().equalsIgnoreCase( expectedColumnType ) )
            throw new HiveTableValidationException( String.format(
              "hive partition schema mismatch: expected column type '%s', but found '%s'", expectedColumnType, schema.getType() ) );
          }
        }
      return true;
      }
    catch( MetaException exception )
      {
      throw new IOException( exception );
      }
    catch( NoSuchObjectException exception )
      {
      return false;
      }
    catch( TException exception )
      {
      throw new IOException( exception );
      }
    finally
      {
      if( metaStoreClient != null )
        metaStoreClient.close();
      }
    }

  @Override
  public boolean deleteResource( Configuration conf ) throws IOException
    {
    forbidIfTransactional();
    // clean up HDFS
    super.deleteResource( conf );

    IMetaStoreClient metaStoreClient = null;
    try
      {
      LOG.info( "dropping hive table {} in database {}", tableDescriptor.getTableName(), tableDescriptor.getDatabaseName() );
      metaStoreClient = metaStoreClientFactory.newInstance( conf );
      metaStoreClient.dropTable( tableDescriptor.getDatabaseName(), tableDescriptor.getTableName(),
        true, true );
      }
    catch( MetaException exception )
      {
      throw new IOException( exception );
      }
    catch( NoSuchObjectException exception )
      {
      throw new IOException( exception );
      }
    catch( TException exception )
      {
      throw new IOException( exception );
      }
    finally
      {
      if( metaStoreClient != null )
        metaStoreClient.close();
      }
    return true;
    }

  /**
   * Registers a new Partition of a HiveTable. If the Partition already exists, it is ignored. If the current
   * table is not partitioned, the call is also ignored.
   *
   * @param conf      Configuration object of the current flow.
   * @param partition The partition to register.
   * @throws IOException In case any interaction with the HiveMetaStore fails.
   */
  void registerPartition( Configuration conf, Partition partition ) throws IOException
    {
    if( !tableDescriptor.isPartitioned() )
      return;

    // throw exception to avoid inconsistent meta store, otherwise the user will end up with a table with 0 partitions
    // in it.
    if( !HadoopUtil.isLocal( conf ) && conf.get( ConfVars.METASTOREURIS.varname ) == null )
      throw new TapException( "Cannot register partition without central metastore. Please set 'hive.metastore.uris' to your metastore." );

    if( !resourceExists( conf ) )
      createHiveTable( conf );

    IMetaStoreClient metaStoreClient = null;
    try
      {
      metaStoreClient = metaStoreClientFactory.newInstance( conf );
      metaStoreClient.add_partition( partition );
      }
    catch( MetaException exception )
      {
      throw new IOException( exception );
      }
    catch( InvalidObjectException exception )
      {
      throw new IOException( exception );
      }
    catch( AlreadyExistsException exception )
      {
      // ignore
      }
    catch( TException exception )
      {
      throw new IOException( exception );
      }
    finally
      {
      if( metaStoreClient != null )
        metaStoreClient.close();
      }
    }

  @Override
  public boolean commitResource( Configuration conf ) throws IOException
    {
    boolean result = true;
    try
      {
      if( !resourceExists( conf ) )
        result = createHiveTable( conf );
      }
    catch( IOException exception )
      {
      throw new TapException( exception );
      }
    return super.commitResource( conf ) && result;
    }

  @Override
  public long getModifiedTime( Configuration conf ) throws IOException
    {
    return modifiedTime;
    }

  /**
   * Internal method to get access to the HiveTableDescriptor of the HiveTap.
   *
   * @return The HiveTableDescriptor.
   */
  HiveTableDescriptor getTableDescriptor()
    {
    return tableDescriptor;
    }

  /**
   * Private method that sets the correct location of the files on HDFS. For an existing table
   * it uses the value from the Hive MetaStore. Otherwise it uses the default location for Hive.
   */
  private void setFilesystemLocation()
    {
    // If the table already exists get the location otherwise use the location from the table descriptor.
    IMetaStoreClient metaStoreClient = null;
    try
      {
      metaStoreClient = metaStoreClientFactory.newInstance( null );
      Table table = metaStoreClient.getTable( tableDescriptor.getDatabaseName(),
        tableDescriptor.getTableName() );
      String path = table.getSd().getLocation();
      setStringPath( path );
      }
    catch( MetaException exception )
      {
      throw new CascadingException( exception );
      }
    catch( NoSuchObjectException exception )
      {
      setStringPath( tableDescriptor.getLocation( HiveConfFactory.getHiveConf( null ).getVar( ConfVars.METASTOREWAREHOUSE ) ) );
      }
    catch( TException exception )
      {
      throw new CascadingException( exception );
      }
    finally
      {
      if( metaStoreClient != null )
        metaStoreClient.close();
      }
    }

  /**
   * Private method that sets the ACID state of the table. For an existing table it uses the
   * value from the Hive MetaStore. Otherwise it uses the default location for Hive.
   */
  private void setTransactional()
    {
    // If the table already exists get the ACID state otherwise use the state from the table descriptor.
    IMetaStoreClient metaStoreClient = null;
    try
      {
      metaStoreClient = metaStoreClientFactory.newInstance( null );
      Table table = metaStoreClient.getTable( tableDescriptor.getDatabaseName(),
        tableDescriptor.getTableName() );
      transactional = Boolean.parseBoolean( table.getParameters().get( HIVE_ACID_TABLE_PARAMETER_KEY ) );
      }
    catch( MetaException exception )
      {
      throw new CascadingException( exception );
      }
    catch( NoSuchObjectException exception )
      {
      transactional = tableDescriptor.isTransactional();
      }
    catch( TException exception )
      {
      throw new CascadingException( exception );
      }
    finally
      {
      if( metaStoreClient != null )
        metaStoreClient.close();
      }
    }

  @Override
  public void sinkConfInit( FlowProcess<? extends Configuration> process, Configuration conf )
    {
    forbidIfTransactional();
    super.sinkConfInit( process, conf );
    }

  @Override
  public void sourceConfInit( FlowProcess<? extends Configuration> process, Configuration conf )
    {
    setTransactionalConfig( conf );
    super.sourceConfInit( process, conf );
    }

  @Override
  public TupleEntryCollector openForWrite( FlowProcess<? extends Configuration> flowProcess, OutputCollector output )
    throws IOException
    {
    forbidIfTransactional();
    return super.openForWrite( flowProcess, output );
    }

  private void forbidIfTransactional()
    {
    if( transactional )
      throw new UnsupportedOperationException( "Writing to an ACID table is not currently supported." );
    }

  void setTransactionalConfig( Configuration conf )
    {
    if( transactional )
      {
      IMetaStoreClient metaStoreClient = null;
      try
        {
        metaStoreClient = metaStoreClientFactory.newInstance( conf );
        ValidTxnList validTxnList = metaStoreClient.getValidTxns();
        conf.set( ValidTxnList.VALID_TXNS_KEY, validTxnList.toString() );
        LOG.debug( "Set ValidTxnList on conf: {}", validTxnList );

        conf.setInt( hive_metastoreConstants.BUCKET_COUNT, tableDescriptor.getBucketCount() );
        LOG.debug( "Set bucket count on conf: {}", tableDescriptor.getBucketCount() );
        }
      catch( MetaException exception )
        {
        throw new TapException( "Could not fetch transaction list from meta store.", exception );
        }
      catch( TException exception )
        {
        throw new TapException( "Could not fetch transaction list from meta store.", exception );
        }
      }
    }

  boolean isTransactional()
    {
    return transactional;
    }

  }
