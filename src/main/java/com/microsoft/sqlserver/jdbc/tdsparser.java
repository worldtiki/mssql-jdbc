//---------------------------------------------------------------------------------------------------------------------------------
// File: tdsparser.java
//
// Contents: A mechanism for parsing TDS response token streams.
// TDSParser scans TDS token streams, deferring processing of individual
// tokens to a token handler.  Token handlers (classes derived from
// TDSTokenHandler) have methods to handle each of the various types of
// TDS tokens.  A method may or may not consume its token.  And the
// the method's return value indicates whether the TDSParser should
// continue scanning (true) or stop (false).  TDSParser calls the special
// token handler method onEOF when it reaches the end of the TDS token stream.
//
// Microsoft JDBC Driver for SQL Server
// Copyright(c) Microsoft Corporation
// All rights reserved.
// MIT License
// Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files(the "Software"), 
//  to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, 
//  and / or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions :
// The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
// THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, 
//  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER 
//  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS 
//  IN THE SOFTWARE.
//---------------------------------------------------------------------------------------------------------------------------------
 

package com.microsoft.sqlserver.jdbc;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The top level TDS parser class.
 */
final class TDSParser
{
  /** TDS protocol diagnostics logger */
  private static Logger logger = Logger.getLogger("com.microsoft.sqlserver.jdbc.internals.TDS.TOKEN");

  /* Parses a TDS token stream from a reader using the supplied token handler.
   * Parsing requires the ability to peek one byte ahead into the token stream
   * to determine the token type of the next token in the stream.  When the
   * token type has been determined, the token handler is called to process the
   * token (or not).  Parsing continues until the token handler says to stop by
   * returning false from one of the token handling methods.
   */
  static void parse(TDSReader tdsReader, String logContext) throws SQLServerException
  {
    parse(tdsReader, new TDSTokenHandler(logContext));
  }

  static void parse(TDSReader tdsReader, TDSTokenHandler tdsTokenHandler) throws SQLServerException
  {
    final boolean isLogging = logger.isLoggable(Level.FINEST);

    // Process TDS tokens from the token stream until we're told to stop.
    boolean parsing = true;
    
    // If TDS_LOGIN_ACK is received verify for TDS_FEATURE_EXTENSION_ACK packet
    boolean isLoginAck = false;
    boolean isFeatureExtAck = false;
    while (parsing)
    {
      int tdsTokenType = tdsReader.peekTokenType();
      if (isLogging)
      {
        logger.finest(tdsReader.toString() + ": " + tdsTokenHandler.logContext + ": Processing " +
          ((-1 == tdsTokenType) ?
           "EOF" :
           TDS.getTokenName(tdsTokenType)));
      }

      switch (tdsTokenType)
      {
        case TDS.TDS_SSPI: parsing = tdsTokenHandler.onSSPI(tdsReader); break;
        case TDS.TDS_LOGIN_ACK: 
        	isLoginAck= true;
        	parsing = tdsTokenHandler.onLoginAck(tdsReader); 
        	break;
        case TDS.TDS_FEATURE_EXTENSION_ACK:
        	isFeatureExtAck = true;
        	tdsReader.getConnection().processFeatureExtAck(tdsReader);
        	parsing = true;
        	break;
        case TDS.TDS_ENV_CHG: parsing = tdsTokenHandler.onEnvChange(tdsReader); break;
        case TDS.TDS_RET_STAT: parsing = tdsTokenHandler.onRetStatus(tdsReader); break;
        case TDS.TDS_RETURN_VALUE: parsing = tdsTokenHandler.onRetValue(tdsReader); break;
        case TDS.TDS_DONEINPROC:
        case TDS.TDS_DONEPROC:
        case TDS.TDS_DONE:
          tdsReader.getCommand().checkForInterrupt();
          parsing = tdsTokenHandler.onDone(tdsReader);
          break;

        case TDS.TDS_ERR: 
        	parsing = tdsTokenHandler.onError(tdsReader); 
        	break;
        case TDS.TDS_MSG: parsing = tdsTokenHandler.onInfo(tdsReader); break;
        case TDS.TDS_ORDER: parsing = tdsTokenHandler.onOrder(tdsReader); break;
        case TDS.TDS_COLMETADATA: parsing = tdsTokenHandler.onColMetaData(tdsReader); break;
        case TDS.TDS_ROW: parsing = tdsTokenHandler.onRow(tdsReader); break;
        case TDS.TDS_NBCROW: parsing = tdsTokenHandler.onNBCRow(tdsReader); break;
        case TDS.TDS_COLINFO: parsing = tdsTokenHandler.onColInfo(tdsReader); break;
        case TDS.TDS_TABNAME: parsing = tdsTokenHandler.onTabName(tdsReader); break;

        case TDS.TDS_FEDAUTHINFO: 
        	parsing = tdsTokenHandler.onFedAuthInfo(tdsReader);
        	break;
        	
        case -1:
          tdsReader.getCommand().onTokenEOF();
          tdsTokenHandler.onEOF(tdsReader);
          parsing = false;
          break;

        default:
          throwUnexpectedTokenException(tdsReader, tdsTokenHandler.logContext);
          break;
      }
    }
    
    // if TDS_FEATURE_EXTENSION_ACK is not received verify if TDS_FEATURE_EXT_AE was sent
    if(isLoginAck && !isFeatureExtAck)
    	tdsReader.TryProcessFeatureExtAck(isFeatureExtAck);
  }

  /* Handle unexpected tokens - throw an exception */
  static void throwUnexpectedTokenException(TDSReader tdsReader, String logContext) throws SQLServerException
  {
	if(logger.isLoggable(Level.SEVERE))
	  logger.severe(tdsReader.toString() + ": " + logContext + ": Encountered unexpected " + TDS.getTokenName(tdsReader.peekTokenType()));
    tdsReader.throwInvalidTDSToken(TDS.getTokenName(tdsReader.peekTokenType()));
  }

  /* Ignore a length-prefixed token */
  static void ignoreLengthPrefixedToken(TDSReader tdsReader) throws SQLServerException
  {
    tdsReader.readUnsignedByte(); // token type
    int envValueLength = tdsReader.readUnsignedShort();
    byte[] envValueData = new byte[envValueLength];
    tdsReader.readBytes(envValueData, 0, envValueLength);
  }
}

/**
 * A default TDS token handler with some meaningful default processing.
 * Other token handlers should subclass from this one to override the defaults
 * and provide specialized functionality.
 *
 * ENVCHANGE_TOKEN
 *   Processes the ENVCHANGE
 *
 * RETURN_STATUS_TOKEN
 *   Ignores the returned value
 *
 * DONE_TOKEN
 * DONEPROC_TOKEN
 * DONEINPROC_TOKEN
 *   Ignores the returned value
 *
 * ERROR_TOKEN
 *   Remember the error and throw a SQLServerException with that error on EOF
 *
 * INFO_TOKEN
 * ORDER_TOKEN
 * COLINFO_TOKEN (not COLMETADATA_TOKEN) 
 * TABNAME_TOKEN
 *   Ignore the token
 *
 * EOF
 *   Throw a database exception with text from the last error token
 *
 * All other tokens
 *   Throw a TDS protocol error exception
 */
class TDSTokenHandler
{
  final String logContext;

  private StreamError databaseError;
  final StreamError getDatabaseError() { return databaseError; }

  TDSTokenHandler(String logContext)
  {
    this.logContext = logContext;
  }

  boolean onSSPI(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.throwUnexpectedTokenException(tdsReader, logContext);
    return false;
  }

  boolean onLoginAck(TDSReader tdsReader) throws SQLServerException
  {
	  TDSParser.throwUnexpectedTokenException(tdsReader, logContext);
	  return false;
  }
  
  boolean onFeatureExtensionAck(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.throwUnexpectedTokenException(tdsReader, logContext);
    return false;
  }
	  
  boolean onEnvChange(TDSReader tdsReader) throws SQLServerException
  {
    tdsReader.getConnection().processEnvChange(tdsReader);
    return true;
  }

  boolean onRetStatus(TDSReader tdsReader) throws SQLServerException
  {
    (new StreamRetStatus()).setFromTDS(tdsReader);
    return true;
  }

  boolean onRetValue(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.throwUnexpectedTokenException(tdsReader, logContext);
    return false;
  }

  boolean onDone(TDSReader tdsReader) throws SQLServerException
  {
    StreamDone doneToken = new StreamDone();
    doneToken.setFromTDS(tdsReader);
    return true;
  }

  boolean onError(TDSReader tdsReader) throws SQLServerException
  {
    if (null == databaseError)
    {
      databaseError = new StreamError();
      databaseError.setFromTDS(tdsReader);
    }
    else
    {
      (new StreamError()).setFromTDS(tdsReader);
    }

    return true;
  }

  boolean onInfo(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.ignoreLengthPrefixedToken(tdsReader);
    return true;
  }

  boolean onOrder(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.ignoreLengthPrefixedToken(tdsReader);
    return true;
  }

  boolean onColMetaData(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.throwUnexpectedTokenException(tdsReader, logContext);
    return false;
  }

  boolean onRow(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.throwUnexpectedTokenException(tdsReader, logContext);
    return false;
  }

  boolean onNBCRow(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.throwUnexpectedTokenException(tdsReader, logContext);
    return false;
  }
  
  boolean onColInfo(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.ignoreLengthPrefixedToken(tdsReader);
    return true;
  }

  boolean onTabName(TDSReader tdsReader) throws SQLServerException
  {
    TDSParser.ignoreLengthPrefixedToken(tdsReader);
    return true;
  }

  void onEOF(TDSReader tdsReader) throws SQLServerException
  {
    if (null != getDatabaseError())
    {
      SQLServerException.makeFromDatabaseError(
        tdsReader.getConnection(),
        null,
        getDatabaseError().getMessage(),
        getDatabaseError(),
        false);
    }
  }
  
  boolean onFedAuthInfo(TDSReader tdsReader) throws SQLServerException
  {
	  tdsReader.getConnection().processFedAuthInfo(tdsReader, this);
	  return true;
  }
}
