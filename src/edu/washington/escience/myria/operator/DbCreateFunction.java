package edu.washington.escience.myria.operator;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.net.URISyntaxException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableMap;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.FilenameUtils;

import edu.washington.escience.myria.DbException;
import edu.washington.escience.myria.MyriaConstants;
import edu.washington.escience.myria.MyriaConstants.FunctionLanguage;
import edu.washington.escience.myria.accessmethod.AccessMethod;
import edu.washington.escience.myria.accessmethod.ConnectionInfo;
import edu.washington.escience.myria.expression.evaluate.GenericEvaluator;
import edu.washington.escience.myria.functions.PythonFunctionRegistrar;
import edu.washington.escience.myria.storage.TupleBatch;
import edu.washington.escience.myria.io.UriSource;
/**
 *
 *Class for creating user defined functions.
 *
 */
public class DbCreateFunction extends RootOperator {

  /** Required for Java serialization. */
  private static final long serialVersionUID = 1L;

  /** The connection to the database database. */
  private AccessMethod accessMethod;
  /** The information for the database connection. */
  private ConnectionInfo connectionInfo;
  /** function name.*/
  private final String name;
  /** function alias.*/
  private final String shortName;
  /** function body.*/
  private final String binary;
  /** Uri of jar file with java location **/
  private final String binaryUri;
  /** function description or text.*/
  private final String description;
  /** does function return multiple tuples.*/
  private final Boolean isMultiValued;
  /** function language.*/
  private final MyriaConstants.FunctionLanguage lang;
  /** function output schema.*/
  private final String outputType;

  /** logger for this class. */
  private static final org.slf4j.Logger LOGGER =
      org.slf4j.LoggerFactory.getLogger(GenericEvaluator.class);

  /**
   * @param child the source of tuples to be inserted.
   * @param name function name.
   * @param connectionInfo the parameters of the database connection.
   * @param outputType output schema for the function
   * @param isMultiValued does it return multiple tuples?
   * @param lang function type
   * @param binary function body (encoded binary string)
   * @param description function decription, this is kept in the catalog and not sent to workers.
   */
  public DbCreateFunction(
      final Operator child,
      final String name,
      final String shortName,
      final String description,
      final String outputType,
      final Boolean isMultiValued,
      final FunctionLanguage lang,
      final String binary,
      final String binaryUri) {
    super(child);
    this.name = name;
    this.shortName = shortName != null ? shortName : name;
    this.description = description;
    this.outputType = outputType;
    this.isMultiValued = isMultiValued;
    this.lang = lang;
    this.binary = binary;
    this.binaryUri = binaryUri;
  }

  @Override
  protected void init(final ImmutableMap<String, Object> execEnvVars)
      throws DbException, IOException, URISyntaxException {
    /* Retrieve connection information from the environment variables, if not already set */
    if (connectionInfo == null && execEnvVars != null) {
      connectionInfo =
          (ConnectionInfo) execEnvVars.get(MyriaConstants.EXEC_ENV_VAR_DATABASE_CONN_INFO);
    }
    switch (lang) {
      case POSTGRES:
        Pattern pattern = Pattern.compile("(CREATE FUNCTION)([\\s\\S]*)(LANGUAGE SQL;)");
        Matcher matcher = pattern.matcher(description);

        if (matcher.matches()) {
          /* Add a replace statement */
          String modifiedReplaceFunction =
              description.replace("CREATE FUNCTION", "CREATE OR REPLACE FUNCTION");

          /* Run command */
          accessMethod.runCommand(modifiedReplaceFunction);
        } else {
          throw new DbException("Postgres function is invalid.");
        }

        break;
      case PYTHON:
        if (binary != null) {
          PythonFunctionRegistrar pyFunc = new PythonFunctionRegistrar(connectionInfo);
          pyFunc.addFunction(name, description, outputType, isMultiValued, binary);
        } else {
          throw new DbException("Cannot register python UDF without binary.");
        }
        break;
      case JAVA:
        if (binaryUri != null) {
          UriSource uriSource = new UriSource(binaryUri);
          InputStream in = uriSource.getInputStream();
          OutputStream out =
              new FileOutputStream(
                  System.getenv("REPO_ROOT") + "/JavaUDF/" + FilenameUtils.getName(binaryUri),
                  false);
          IOUtils.copy(in, out);
          in.close();
          out.close();
        }
        break;
      default:
        throw new DbException("Function language not supported!");
    }
  }

  @Override
  public void cleanup() {
    try {
      if (accessMethod != null) {
        accessMethod.close();
      }
    } catch (DbException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void consumeTuples(final TupleBatch tuples) throws DbException {}

  @Override
  protected void childEOS() throws DbException {}

  @Override
  protected void childEOI() throws DbException {}
}
