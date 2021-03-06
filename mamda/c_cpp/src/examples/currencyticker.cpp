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

#include <mama/mamacpp.h>
#include <mamda/MamdaSubscription.h>
#include <mamda/MamdaCurrencyListener.h>
#include <mamda/MamdaCurrencyFields.h>
#include <mamda/MamdaCurrencyHandler.h>
#include <mamda/MamdaCurrencyRecap.h>
#include <mamda/MamdaCurrencyUpdate.h>
#include <mamda/MamdaErrorListener.h>
#include <mamda/MamdaQualityListener.h>
#include <iostream>
#include <stdexcept>
#include <vector>
#include "parsecmd.h"
#include "mama/MamaQueueGroup.h"
#include "dictrequester.h"

using std::exception;
using std::vector;
using std::cerr;
using std::cout;

using namespace Wombat;

void usage (int exitStatus);

class CurrencyTicker : public MamdaCurrencyHandler
                           , public MamdaErrorListener
                           , public MamdaQualityListener
{
public:
    virtual ~CurrencyTicker () {}
    
               
    void onCurrencyRecap (
        MamdaSubscription*          subscription,
        MamdaCurrencyListener&      listener,
        const MamaMsg&              msg,
        const MamdaCurrencyRecap&   recap)
        {
             cout << "Currency Recap (" 
                  << subscription->getSymbol () 
                  << ") \n  BidPrice: "
                  << recap.getBidPrice().getAsString()
                  << "(" << recap.getBidPriceFieldState() << ")"
                  << ", AskPrice:"
                  << recap.getAskPrice().getAsString()
                  << "(" << recap.getAskPriceFieldState() << ")"
                  << "\n"
                  << flush;         
        }
        
    void onCurrencyUpdate ( 
        MamdaSubscription*          subscription,
        MamdaCurrencyListener&      listener,
        const MamaMsg&              msg,
        const MamdaCurrencyRecap&   recap,
        const MamdaCurrencyUpdate&  update)
        
        {
            cout << "\n BidPrice:"
                 << update.getBidPrice().getAsString()
                 << "(" << recap.getBidPriceFieldState() << ")"
                 << ", AskPrice:"
                 << update.getAskPrice().getAsString()
                 << "(" << recap.getAskPriceFieldState() << ")"
                 << "\n"
                 << flush;         
}


    void onError (
        MamdaSubscription*   subscription,
        MamdaErrorSeverity   severity,
        MamdaErrorCode       errorCode,
        const char*          errorStr)
    {
        cout << "Error (" << subscription->getSymbol () << ")";
    }

    void onQuality (
        MamdaSubscription*   subscription,
        mamaQuality          quality)
    {
        cout << "Quality (" << subscription->getSymbol () << "): " << quality;
    }
    
};


int main (int argc, const char **argv)
{
    try
    {
        CommonCommandLineParser     cmdLine (argc, argv);
        // Initialise the MAMA API
        mamaBridge bridge = cmdLine.getBridge();
        Mama::open ();
        const vector<const char*>&  symbolList = cmdLine.getSymbolList ();
        MamaSource*                 source     = cmdLine.getSource();
        MamaQueueGroup   queues (cmdLine.getNumThreads(), bridge);
        DictRequester    dictRequester (bridge);
        dictRequester.requestDictionary (cmdLine.getDictSource());

        MamdaCurrencyFields::setDictionary (*dictRequester.getDictionary ());
        MamdaCommonFields::setDictionary (*dictRequester.getDictionary());
        
        const char* symbolMapFile = cmdLine.getSymbolMapFile ();
        if (symbolMapFile)
        {
            MamaSymbolMapFile* aMap = new MamaSymbolMapFile;
            if (MAMA_STATUS_OK == aMap->load (symbolMapFile))
            {
                source->getTransport()->setSymbolMap (aMap);
            }
        }

        for (vector<const char*>::const_iterator i = symbolList.begin ();
            i != symbolList.end ();
            ++i)
        {
            const char* symbol = *i;
            MamdaSubscription* aSubscription = new MamdaSubscription;
            MamdaCurrencyListener* aListener = new MamdaCurrencyListener();
            CurrencyTicker* aTicker = new CurrencyTicker();
            
            aListener->addHandler (aTicker);
            aSubscription->addMsgListener   (aListener);
            aSubscription->addQualityListener (aTicker);
            aSubscription->addErrorListener (aTicker);
            aSubscription->create (queues.getNextQueue(), source, symbol);   
        }

        Mama::start(bridge);
    }
    catch (MamaStatus &e)
    {
        // This exception can be thrown from Mama.open (),
        // Mama::createTransport (transportName) and from
        // MamdaSubscription constructor when entitlements is enabled.
        cerr << "Exception in main (): " << e.toString () << endl;
        exit (1);
    }
    catch (exception &ex)
    {
        cerr << "Exception in main (): " << ex.what () << endl;
        exit (1);
    }
    catch (...)
    {
        cerr << "Unknown Exception in main ()." << endl;
        exit (1);
    }
}

void usage (int exitStatus)
{
    std::cerr << "Usage: currencyticker [-tport] tport_name [-m] middleware [-S] source [-s] symbol [options] \n";   
    exit (exitStatus);
}



