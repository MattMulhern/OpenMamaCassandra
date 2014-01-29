/* $Id$
 *
 * OpenMAMA: The open middleware agnostic messaging API
 * Copyright (C) 2011 NYSE Technologies, Inc.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA
 * 02110-1301 USA
 */

package com.wombat.mama.examples;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.io.*;

import com.wombat.mama.*;

public class MamaCassandraListen
{
    private static MamaTransport    transport;
    private static MamaTransport    myDictTransport;
    private static String           myMiddleware        = "wmw";
    private static MamaBridge       myBridge            = null;
    private static MamaQueue        myDefaultQueue      = null;

    private static MamaDictionary   dictionary;
    private static String           dictSource          = "WOMBAT";
    private static String           dictFile            = null;
    private static final ArrayList  fieldList           = new ArrayList();
    private static boolean          dictionaryComplete  = false;

    private static String           transportName       = "internal";
    private static String           myDictTportName     = null;
    private static boolean          requireInitial      = true;
    private static int              quietness           = 0;

    private static int              numThreads          = 0;
    private static MamaQueueGroup   queueGroup          = null;

    private static final ArrayList  subscriptions       = new ArrayList();
    private static final ArrayList  subjectList         = new ArrayList();
    private static       String     filename            = null;

    private static String           mySymbolNamespace   = null;

    private static final SubscriptionCallback   
        callback            = new SubscriptionCallback();

    private static final Logger     logger              = 
                                    Logger.getLogger( "com.wombat.mama" );
    private static Level            myLogLevel;
    private static MamaSource       mySource            = null;   
    private static MamaSource       myDictSource        = null;

    private static MamaCassandra    cass                = null;
    private static int              indent              = 1;

    
    /* Contains the amount of time that the example program will run for, if set to 0 then it
     * will run indefinitely.
     */

    public static void main (final String[] args)
    {
        cass = new MamaCassandra();
        cass.connect();
        cass.clear();

        parseCommandLine (args);

        try
        {
            if (subjectList.isEmpty())
            {
                readSubjectsFromFile();
            }

            initializeMama ();
            if (dictFile != null)
            {
                System.out.println("Dictionary file specified, building dictionary from " + dictFile);
                dictionary= new MamaDictionary();
                dictionary.populateFromFile(dictFile);
            }
            else
            {
                buildDataDictionary ();
            }
            subscribeToSubjects ();
            
            System.out.println( "Type CTRL-C to exit." );
            
            Mama.start (myBridge);
        }
        catch (Exception e)
        {
            if (e.getCause() != null)
            {
                e.getCause ().printStackTrace ();
            }
            e.printStackTrace ();
            System.exit (1);
        }
        finally
        {
            shutdown ();
        }
    }

    private static void subscribeToSubjects()
    {
        int howMany = 0;

        queueGroup = new MamaQueueGroup (numThreads, myBridge);

        /*Subscribe to all symbols specified on the command line or from the
          symbol file*/
        for (Iterator iterator = subjectList.iterator(); iterator.hasNext();)
        {
            final String symbol = (String) iterator.next();
            
            MamaSubscription subscription = new MamaSubscription ();
           
            /*Properties common to all subscription types*/
            subscription.setRequiresInitial (requireInitial);
            subscription.createSubscription (callback,
                                             queueGroup.getNextQueue (),
                                             mySource,
                                             symbol,
                                             null);

            
            if(quietness < 1)
            {
                System.out.println("Subscribed to: " + symbol);
            }
            if (++howMany % 1000 == 0)
            {
                System.out.println ("Subscribed to " + howMany + " symbols.");
            }
        }
    }

    private static void buildDataDictionary() throws InterruptedException
    {
        MamaDictionaryCallback dictionaryCallback = createDictionaryCallback();

        synchronized (dictionaryCallback)
        {
            /* The dictionary is obtained through a specialized form of
             * subscription. */
            MamaSubscription subscription = new MamaSubscription ();

            dictionary = subscription.createDictionarySubscription (
                                      dictionaryCallback,
                                      myDefaultQueue,
                                      myDictSource,
                                      10.0,
                                      2);
            Mama.start (myBridge);
            if (!dictionaryComplete) dictionaryCallback.wait( 30000 );
            if (!dictionaryComplete)
            {
                System.err.println( "Timed out waiting for dictionary." );
                System.exit( 0 );
            }
        }
    }

    private static MamaDictionaryCallback createDictionaryCallback()
    {
        return new MamaDictionaryCallback()
        {
            public void onTimeout()
            {
                System.err.println( "Timed out waiting for dictionary" );
                System.exit(1);
            }

            public void onError (final String s)
            {
                System.err.println ("Error getting dictionary: " + s);
                System.exit (1);
            }

            public synchronized void onComplete()
            {
                dictionaryComplete = true;
                Mama.stop(myBridge);
                notifyAll();
            }
        };
    }

    private static void shutdown()
    {
        try
        {
            // Destroy all the subscriptions. */
            for (Iterator iterator = subscriptions.iterator(); iterator.hasNext();)
            {
                final MamaSubscription subscription = (MamaSubscription) iterator.next();
                try
                {
                    subscription.destroy();
                }
                catch(Throwable t)
                {
                    t.printStackTrace();
                }
            }
            
            /* Destroy all the queues. */
            if((queueGroup != null) && (numThreads > 0))
            {
                queueGroup.destroyWait();
            }

            /* Destroy the transport. */
            if (transport != null)
            {
                transport.destroy();
            }

            /* Perform remaining cleanup */
            Mama.close();
        } 
        catch(Throwable ex)
        {
            ex.printStackTrace();
        }
    }

    private static void initializeMama()
    {
        try
        {
            myBridge = Mama.loadBridge (myMiddleware);
            Mama.open ();
            myDefaultQueue = Mama.getDefaultQueue (myBridge);
        }
        catch (Exception e)
        {
            e.printStackTrace ();
            System.out.println ("Failed to initialize MAMA");
            System.exit (1);
        }
        
        transport = new MamaTransport ();

        if (myDictTportName != null)
        {
            myDictTransport = new MamaTransport ();
            myDictTransport.create (myDictTportName, myBridge);
        }
        else 
        {
            myDictTransport = transport;
        }

        /*Receive notification of transport level events*/
        transport.addTransportListener( new MamaTransportListener()
        {
            public void onConnect(short cause, final Object platformInfo)
            {
                System.out.println ("TRANSPORT CONNECTED!");
            }

            public void onDisconnect(short cause, final Object platformInfo)
            {
                System.out.println ("TRANSPORT DISCONNECTED!");
            }

            public void onReconnect(short cause, final Object platformInfo)
            {
                System.out.println ("TRANSPORT RECONNECTED!");
            }

            public void onPublisherDisconnect(short cause, final Object platformInfo)
            {
                System.out.println ("PUBLISHER DISCONNECTED!");
            }

            public void onAccept(short cause, final Object platformInfo)
            {
                System.out.println ("TRANSPORT ACCEPTED!");
            }

            public void onAcceptReconnect(short cause, final Object platformInfo)
            {
                System.out.println ("TRANSPORT RECONNECT ACCEPTED!");
            }

            public void onNamingServiceConnect(short cause, final Object platformInfo)
            {
                System.out.println ("NSD CONNECTED!");
            }

            public void onNamingServiceDisconnect(short cause, final Object platformInfo)
            {
                System.out.println ("NSD DISCONNECTED!");
            }

            public void onQuality(short cause, final Object platformInfo)
            {
                System.out.println ("TRANSPORT QUALITY!");
                short quality = transport.getQuality();
                System.out.println ("Transport quality is now " +
                                    MamaQuality.toString(quality) +
                                    ", cause " + MamaDQCause.toString (cause) +
                                    ", platformInfo: " + platformInfo);
            }
        } );

        /*The name specified here is the name identifying properties in the
         * mama.properties file*/
        transport.create (transportName, myBridge);

        /* MamaSource for all subscriptions */
        mySource     = new MamaSource ();
        mySource.setTransport (transport);
        mySource.setSymbolNamespace (mySymbolNamespace);

        /* MamaSource for dictionary subscription */
        myDictSource = new MamaSource ();
        myDictSource.setTransport (myDictTransport);
        myDictSource.setSymbolNamespace (dictSource);
    }

    private static void readSubjectsFromFile() throws IOException
    {
        InputStream input;
        String      symbol;
        if (filename != null)
        {
            input = new FileInputStream (filename);
        }
        else
        {
            input = System.in;
            System.out.println ("Enter one symbol per line and terminate with a .");
            System.out.print ("SUBJECT>");
        }

        final BufferedReader reader =
                new BufferedReader (new InputStreamReader (input));

        while (null != (symbol = reader.readLine()))
        {
            if (!symbol.equals(""))
            {
                if (symbol.equals( "." ))
                {
                    break;
                }
                subjectList.add (symbol);
            }

            if (input == System.in)
            {
                System.out.print ("SUBJECT>");
            }
        }

        if (subjectList.isEmpty())
        {
            System.err.println ("No subjects specified");
            System.exit (1);
        }
    }

    private static void print (final String what, final int width)
    {
        if(quietness < 1)
        {
            int whatLength = 0;
            if (what!=null)
                whatLength = what.length();

            StringBuffer sb = new StringBuffer (what);

            final int spaces = width - whatLength;

            for (int i = 0; i < spaces; i++) sb.append (" ");
            
            System.out.print (sb.toString());
        }
    }

    private static void parseCommandLine (final String[] args)
    {
        for(int i = 0; i < args.length;)
        {
            if (args[i].equals ("-source") || args[i].equals("-S"))
            {
                mySymbolNamespace = args[i +1];
                i += 2;
            }
            else if (args[i].equals ("-d") || args[i].equals("-dict_source"))
            {
                dictSource = args[i + 1];
                i += 2;
            }
            else if (args[i].equals ("-dict_tport"))
            {
                myDictTportName = args[i + 1];
                i += 2;
            }
            else if (args[i].equals("-dictionary"))
            {
                dictFile = args[i + 1];
                i += 2;
            }
            else if (args[i].equals ("-I"))
            {
                requireInitial = false;
                i++;
            }
            else if (args[i].equals ("-s"))
            {
                subjectList.add (args[i + 1]);
                i += 2;
            }
            else if (args[i].equals ("-f"))
            {
                filename = args[i + 1];
                i += 2;
            }
            else if (args[i].equals ("-tport"))
            {
                transportName = args[i + 1];
                i += 2;
            }
            else if (args[i].equals ("-threads"))
            {
                numThreads = Integer.parseInt (args[i+1]);
                i += 2;
            }
            else if (args[i].equals ("-q"))
            {
                myLogLevel = myLogLevel == null
                            ? Level.WARNING : myLogLevel == Level.WARNING   
                            ? Level.SEVERE  : Level.OFF;
                
                Mama.enableLogging (myLogLevel);
                quietness++;
                i++;
            }
            else if (args[i].equals ("-v"))
            {
                myLogLevel = myLogLevel == null
                            ? Level.FINE    : myLogLevel == Level.FINE    
                            ? Level.FINER   : Level.FINEST;

                Mama.enableLogging (myLogLevel);
                i++;
            }
            else if (args[i].equals ("-m"))
            {
                myMiddleware = args[i + 1];
                i += 2;
            }
            else
            {
                fieldList.add (args[i]);
                i++;
            }
        }
    }

    /*Class for processing all event callbacks for all subscriptions*/
    private static class SubscriptionCallback implements MamaSubscriptionCallback
    {
        public void onMsg (final MamaSubscription subscription, final MamaMsg msg)
        {
            try
            {
                switch (MamaMsgType.typeForMsg (msg))
                {
                    case MamaMsgType.TYPE_DELETE:
                    case MamaMsgType.TYPE_EXPIRE:
                        subscription.destroy ();
                        subscriptions.remove (subscription);
                        return;
                }

                switch (MamaMsgStatus.statusForMsg (msg))
                {
                    case MamaMsgStatus.STATUS_BAD_SYMBOL:
                    case MamaMsgStatus.STATUS_EXPIRED:
                    case MamaMsgStatus.STATUS_TIMEOUT:
                        subscription.destroy();
                        subscriptions.remove (subscription);
                        return;
                }
            }
            catch (Exception ex) 
            {
                ex.printStackTrace ();
                System.exit (0);
            }
            
            cass.writeMsg(msg, dictionary, subscription);
            
            if (quietness < 1)
            {
                System.out.println (subscription.getSymbol () +
                    " Type: " + MamaMsgType.stringForType (msg) +
                    " Status: " + MamaMsgStatus.stringForStatus (msg));
            }

            
            if (fieldList.size() == 0)
            {
                displayAllFields (msg);
            }
            else
            {
                displayFields (msg, fieldList);
            }
        }
            
        /* Invoked once the subscrption request has been dispatched from the
         * throttle queue. Required by underlying SubscriptionCallback class. */
        public void onCreate (MamaSubscription subscription)
        {
            subscriptions.add (subscription);
        }

        /* Invoked if any errors are encountered during subscription processing.
         * Required by underlying SubscriptionCallback class. */
        public void onError(MamaSubscription subscription,
                            short            mamaStatus,
                            int              tibrvStatus, 
                            String           subject, 
                            Exception        e)
        {
            System.err.println ("Symbol=[" + subscription.getSymbol() + "] : " +
                                "An error occurred creating subscription: " +
                                MamaStatus.stringForStatus (mamaStatus));

        }

        /* Invoked if the quality status for the subscription changes.
         * Required by underlying SubscriptionCallback class. */
        public void onQuality (MamaSubscription subscription, short quality,
                               short cause, final Object platformInfo)
        {
            System.err.println( subscription.getSymbol () + 
                                ": quality is now " +
                                MamaQuality.toString (quality) +
                                ", cause " + cause +
                                ", platformInfo: " + platformInfo);
        }
        
        /* Invoked on sequence number gap detection.
         * Required by underlying SubscriptionCallback class. */
        public void onGap (MamaSubscription subscription)
        {
            System.err.println (subscription.getSymbol () + ": gap detected ");
        }
        
        /* Invoked when recap requested.
         * Required by underlying SubscriptionCallback class. */
        public void onRecapRequest (MamaSubscription subscription)
        {
            System.err.println (subscription.getSymbol () + ": recap requested ");
        }

        /* Invoked when subscription is called.
         * Required by underlying SubscriptionCallback class. */
        public void onDestroy (MamaSubscription subscription)
        {
            System.out.println ("Subscription destroyed");
        }
    }

    /*Class for processing fields within a message - for the message
     * iterator*/
    private class FieldIterator implements MamaMsgFieldIterator
    {
        public void onField (MamaMsg        msg,
                             MamaMsgField   field,
                             MamaDictionary dictionary,
                             Object         closure)
        {
            try
            {
                indent();
                print (field.getName(),20);
                print (" | ", 0);
                print ("" + field.getFid(),4);
                print (" | ", 0);
                print ("" + field.getTypeName(),10);
                print (" | ", 0);

                printFromField (field);

                /* If it was a VECTOR_MSG field, we've already 'newlined' */
                if (field.getType() != MamaFieldDescriptor.VECTOR_MSG)
                print (" \n ", 0);

            }
            catch (Exception ex)
            {
                ex.printStackTrace();
            }
        }

        /* Access the data from the field objects */
        private void printFromField (MamaMsgField field)
        {
            short fieldType = field.getType ();
            switch (fieldType)
            {
                case MamaFieldDescriptor.BOOL:
                    print ("" + field.getBoolean(), 20);
                    break;
                case MamaFieldDescriptor.CHAR:
                    print ("" + field.getChar(), 20);
                    break;
                case MamaFieldDescriptor.I8:
                    print ("" + field.getI8(), 20);
                    break;
                case MamaFieldDescriptor.U8:
                    print ("" + field.getU8(), 20);
                    break;
                case MamaFieldDescriptor.I16:
                    print ("" + field.getI16(), 20);
                    break;
                case MamaFieldDescriptor.U16:
                    print ("" + field.getU16(), 20);
                    break;
                case MamaFieldDescriptor.I32:
                    print ("" + field.getI32(), 20);
                    break;
                case MamaFieldDescriptor.U32:
                    print ("" + field.getU32(), 20);
                    break;
                case MamaFieldDescriptor.I64:
                    print ("" + field.getI64(), 20);
                    break;
                case MamaFieldDescriptor.U64:
                    print ("" + field.getU64(), 20);
                    break;
                case MamaFieldDescriptor.F32:
                    print ("" + field.getF32(), 20);
                    break;
                case MamaFieldDescriptor.F64:
                    print ("" + field.getF64(), 20);
                    break;
                case MamaFieldDescriptor.STRING:
                    print (field.getString(), 20);
                    break;
                case MamaFieldDescriptor.TIME:
                    print ("" + field.getDateTime (), 20);
                    break;
                case MamaFieldDescriptor.PRICE:
                    print ("" + field.getPrice (), 20);
                    break;
                      case MamaFieldDescriptor.VECTOR_MSG:
                    printVectorMessage(field);
                    break;
                default:
                    print ("Unknown type: " + fieldType, 20);
            }
        }
    }

    public static void displayFields (final MamaMsg          msg,
                               final ArrayList        fieldList)
    {

        for (Iterator iterator = fieldList.iterator(); iterator.hasNext();)
        {
            final String name = (String) iterator.next();

            MamaFieldDescriptor field = dictionary.getFieldByName (name);

            displayField (field, msg);
        }
    }

    private static synchronized void displayField (MamaFieldDescriptor fieldDesc,
                                            final MamaMsg msg)
    {
        String fieldName = fieldDesc.getName ();
        int fid = fieldDesc.getFid ();
        MamaMsgField field = msg.getField (fieldName,
                                           fid,
                                           dictionary);

        if (field == null                                   ||
            field.getType() == MamaFieldDescriptor.U32ARRAY ||
            field.getType() == MamaFieldDescriptor.U16ARRAY ||
            field.getType() == MamaFieldDescriptor.MSG)
        {
            return;
        }

        if (quietness < 1)
        {
            System.out.print ("\t");
            print ( ((null == fieldName) ? "unknown" : fieldName), 20);
            System.out.print (" | ");
            print ("" + fid, 4);
            System.out.print (" | ");
            print (field.getTypeName (), 10);
            System.out.print (" | ");
            try
            {
                switch (field.getType())
                {
                    case MamaFieldDescriptor.CHAR:
                        System.out.println (msg.getChar(fieldName, fid));
                        break;
                    case MamaFieldDescriptor.U8:
                        System.out.println (msg.getU8(fieldName, fid));
                        break;
                    case MamaFieldDescriptor.I16:
                        System.out.println (msg.getI16(fieldName, fid));
                        break;
                    case MamaFieldDescriptor.I32:
                        System.out.println (msg.getI32(fieldName, fid));
                        break;
                    case MamaFieldDescriptor.U32:
                        System.out.println (msg.getU32(fieldName, fid));
                        break;
                    case MamaFieldDescriptor.I64:
                        System.out.println (msg.getI64(fieldName, fid));
                        break;
                    case MamaFieldDescriptor.U64:
                        System.out.println (msg.getU64(fieldName, fid));
                        break;
                    case MamaFieldDescriptor.F64:
                        System.out.println (msg.getF64(fieldName, fid));
                        break;
                    case MamaFieldDescriptor.STRING:
                        System.out.println (msg.getString(fieldName, fid));
                        break;
                    case MamaFieldDescriptor.TIME:
                        System.out.println (msg.getDateTime (fieldName, fid));
                        break;
                    case MamaFieldDescriptor.PRICE:
                        System.out.println (msg.getPrice (fieldName, fid));
                        break;
                    default:
                        System.out.println (
                                msg.getFieldAsString (fid, dictionary));
                }
            }
            catch (MamaFieldNotFoundException e)
            {
                System.out.println ("Field not found in message.");
            }
        }
    }

    private static synchronized void displayAllFields(
            final MamaMsg          msg )
    {
        if (quietness < 2)
        {
            for (Iterator iterator=msg.iterator(dictionary); iterator.hasNext();)
            {
                MamaMsgField field = (MamaMsgField) iterator.next();
                try
                {
                    indent();
                    print (field.getName(),20);
                    print (" | ", 0);
                    print ("" + field.getFid(),4);
                    print (" | ", 0);
                    print ("" + field.getTypeName(),10);
                    print (" | ", 0);
                    displayMamaMsgField (field);

                    /* if it was a VECTOR_MSG field, we've already 'newlined' */
                    if (field.getType() != MamaFieldDescriptor.VECTOR_MSG)
                    print (" \n ", 0);
                }
                catch (Exception ex)
                {
                    ex.printStackTrace();
                }
            }
        }
    }

    private static void indent()
    {
        for (int i=0;i<indent;i++)
            print("   ", 0);
    }

    private static void displayMamaMsgField (MamaMsgField field)
    {
        short fieldType = field.getType ();
        switch (fieldType)
        {
            case MamaFieldDescriptor.BOOL:
                print ("" + field.getBoolean(), 20);
                break;
            case MamaFieldDescriptor.CHAR:
                print ("" + field.getChar(), 20);
                break;
            case MamaFieldDescriptor.I8:
                print ("" + field.getI8(), 20);
                break;
            case MamaFieldDescriptor.U8:
                print ("" + field.getU8(), 20);
                break;
            case MamaFieldDescriptor.I16:
                print ("" + field.getI16(), 20);
                break;
            case MamaFieldDescriptor.U16:
                print ("" + field.getU16(), 20);
                break;
            case MamaFieldDescriptor.I32:
                print ("" + field.getI32(), 20);
                break;
            case MamaFieldDescriptor.U32:
                print ("" + field.getU32(), 20);
                break;
            case MamaFieldDescriptor.I64:
                print ("" + field.getI64(), 20);
                break;
            case MamaFieldDescriptor.U64:
                print ("" + field.getU64(), 20);
                break;
            case MamaFieldDescriptor.F32:
                print ("" + field.getF32(), 20);
                break;
            case MamaFieldDescriptor.F64:
                print ("" + field.getF64(), 20);
                break;
            case MamaFieldDescriptor.STRING:
                print (field.getString(), 20);
                break;
            case MamaFieldDescriptor.TIME:
                print ("" + field.getDateTime (), 20);
                break;
            case MamaFieldDescriptor.PRICE:
                print ("" + field.getPrice (), 20);
                break;
            case MamaFieldDescriptor.VECTOR_MSG:
                printVectorMessage(field);
                break;
            default:
                print ("Unknown type: " + fieldType, 20);
        }
    }

    private static void printVectorMessage(MamaMsgField field)
    {
        MamaMsg[] vMsgs = field.getArrayMsg();
        print("\n",0);
        for (int i =0; i!= vMsgs.length; i++)
        {
            indent();
            print("{", 0);
            print("\n",0);
            indent++;
            displayAllFields (vMsgs[i]);
            indent--;
            indent();
            print("}\n", 0);
        }
    }
}
