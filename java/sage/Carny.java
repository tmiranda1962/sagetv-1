/*
 * Copyright 2015 The SageTV Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package sage;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class Carny implements Runnable
{
  private static final String GLOBAL_WATCH_COUNT = "global_watch_count";
  private static final String LIMITED_CARNY_INIT = "limited_carny_init";
  // The user can define exactly how many threads will be used. This is not checked against the
  // total core count, so the user could put in a number that's more if they want to experiment.
  // There is no evidence that that would have a positive effect.
  private static final String LIMITED_CARNY_THREADS = "limited_carny_threads";
  // This disables the mapping optimizations in favor of lower memory usage. In my own measurements
  // with about 64k airings and 12k agents, the maps consumed about 2MB. (JS)
  private static final String DISABLE_CARNY_MAPS = "disable_carny_maps";
  static final String CARNY_KEY = "carny";

  private static final long LOOKAHEAD = Sage.EMBEDDED ? 10*24*60*60*1000L : 14*24*60*60*1000L;
  /*
   * NOTE: I CHANGED THIS ON 7/22/03. I THINK IT'S WHY ITS RECORDING
   * EXTRA STUFF, BECAUSE ANYTHING WILL COME THROUGH WITH AT LEAST THIS PROBABILITY
   */
  private static final float MIN_WP = 0;//1e-6f;
  public static final long SLEEP_PERIOD = Sage.EMBEDDED ? 120 : 30;
  // The processor count is used to limit the number of threads we will spin up for agent processing.
  public static final int PROCESSOR_COUNT = Math.max(Runtime.getRuntime().availableProcessors(), 1);

  public static final Object WATCH_MARK_JOB = new Object();
  public static final Object WATCH_REAL_JOB = new Object();
  public static final Object WATCH_CLEAR_JOB = new Object();
  public static final Object STD_JOB = new Object();
  public static final Object REQUIRED_JOB = new Object();
  public static final Object LOVE_JOB = new Object();
  public static final Object LOVE_CLEAR_JOB = new Object();
  public static final Object WASTED_JOB = new Object();

  private static String getJobName(Object jobby)
  {
    if (jobby == WATCH_MARK_JOB)
      return "WatchMark";
    else if (jobby == WATCH_REAL_JOB)
      return "WatchReal";
    else if (jobby == WATCH_CLEAR_JOB)
      return "WatchClear";
    else if (jobby == LOVE_JOB)
      return "Love";
    else if (jobby == LOVE_CLEAR_JOB)
      return "LoveClear";
    else if (jobby == STD_JOB)
      return "Std";
    else if (jobby == REQUIRED_JOB)
      return "Req";
    else if (jobby == WASTED_JOB)
      return "Wasted";
    else return "Unknown";
  }

  public static Carny prime()
  {
    return getInstance();
  }

  private static class CarnyHolder
  {
    public static final Carny instance = new Carny();
  }

  public static Carny getInstance()
  {
    return CarnyHolder.instance;
  }

  private Carny()
  {
    prefs = CARNY_KEY + '/';
    globalWatchCount = Sage.getInt(prefs + GLOBAL_WATCH_COUNT, 0);
    jobs = new Vector<Object[]>();
    alive = true;
    wiz = Wizard.getInstance();
    trends = Sage.EMBEDDED ? new int[0] : new int[] { Agent.TITLE_MASK | Agent.FIRSTRUN_MASK, Agent.TITLE_MASK | Agent.RERUN_MASK,
        Agent.CHANNEL_MASK | Agent.CATEGORY_MASK,
        Agent.NETWORK_MASK | Agent.CATEGORY_MASK, Agent.ACTOR_MASK,
        Agent.CHANNEL_MASK | Agent.PR_MASK, Agent.CATEGORY_MASK | Agent.PR_MASK };
    wpMap = Collections.synchronizedMap(new HashMap<Airing, Float>());
    causeMap = Collections.synchronizedMap(new HashMap<Airing, Agent>());
    pots = Pooler.EMPTY_AIRING_ARRAY;
    mustSeeSet = Collections.synchronizedSet(new HashSet<Airing>());
    loveAirSet = Collections.synchronizedSet(new HashSet<Airing>());
    swapMap = new HashMap<Airing, Airing>();
    agentWorkQueue = new ConcurrentLinkedQueue<>();
    agentWorkers = Executors.newCachedThreadPool(new ThreadFactory()
    {
      @Override
      public Thread newThread(Runnable r)
      {
        Thread newThread = new Thread(r);
        newThread.setName("CarnyAgentWorker");
        return newThread;
      }
    });
    prepped = false;
  }

  void lengthyInit(boolean updateStatus)
  {
    if (Sage.client)
    {
      alive = false;
      return;
    }
    if (!updateStatus) doneInit = true;
    stdProcessing();
    doneInit = true;
    if (Sage.getBoolean(LIMITED_CARNY_INIT, true))
      kick();
  }

  public void run()
  {
    if (!doneInit)
    {
      lengthyInit(false);
      SchedulerSelector.getInstance().kick(false);
    }
    while (alive)
    {
      try{

        synchronized (jobs)
        {
          if (jobs.isEmpty())
          {
            if (Sage.DBG) System.out.println("Carny waiting for awhile...");
            try{jobs.wait(60*60000L);}catch(InterruptedException e){}
            continue;
          }
        }
        while (!jobs.isEmpty())
        {
          Object[] currJob = jobs.remove(0);
          if (Sage.DBG) System.out.println("Carny got a " + getJobName(currJob[0]) + " job of " + currJob[1]);
          if (currJob[0] == WATCH_MARK_JOB ||
              currJob[0] == WATCH_REAL_JOB)
          {
            applyWatchData((Airing) currJob[1]);
          }
          else if (currJob[0] == WASTED_JOB)
            applyWasteData((Airing) currJob[1]);
          synchronized (jobs) { jobs.notifyAll(); }
        }
        stdProcessing();

      }catch(Throwable t)
      {
        System.out.println("CARNY ERROR:" + t);
        Sage.printStackTrace(t);
      }
    }
  }

  void goodbye()
  {
    alive = false;
    synchronized (jobs)
    {
      jobs.notifyAll();
    }
    // alive being changed to false will be enough to cause a graceful termination of the agent
    // worker threads.
    agentWorkers.shutdown();
  }

  // Any non-standard processing we should do immediately, profiling calculations
  // can wait, it may also affect them and they'd need to be repeated anyways....
  private boolean shouldContinueStdProcessing(boolean underTimeLimit)
  {
    if (!alive) return false;
    synchronized (jobs)
    {
      for (int i = 0; i < jobs.size(); i++)
        if (jobs.elementAt(i)[0] != STD_JOB)
        {
          if (underTimeLimit || jobs.elementAt(i)[0] == LOVE_JOB)
            return false;
        }
    }
    return true;
  }

  void incWatchCount()
  {
    if (!Sage.EMBEDDED)
    {
      synchronized (GLOBAL_WATCH_COUNT)
      {
        Sage.putInt(prefs + GLOBAL_WATCH_COUNT, ++globalWatchCount);
      }
    }
  }

  /*
   * This ENSURES we don't have problems with negative watch counts
   */
  protected void notifyOfWatchCount(int wCount)
  {
    if (wCount >= globalWatchCount)
    {
      globalWatchCount = wCount + 1;
      if (!Sage.EMBEDDED)
      {
        Sage.putInt(prefs + GLOBAL_WATCH_COUNT, globalWatchCount);
      }
    }
  }


  public Agent addFavorite(int agentMask, String title, String category, String subCategory,
      Person person, int role, String rated, String year, String pr, String network,
      String chanName, int slotType, int[] timeslots, String keyword)
  {
    if (SageConstants.LITE) return null;
    Agent rv = null;
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      DBObject[] allAgents = wiz.getRawAccess(Wizard.AGENT_CODE, Wizard.AGENTS_BY_CARNY_CODE);
      Agent bond = new Agent(0);
      bond.agentMask = agentMask;
      bond.title = wiz.getTitleForName(title);
      bond.category = wiz.getCategoryForName(category);
      bond.subCategory = wiz.getSubCategoryForName(subCategory);
      bond.person = person;
      bond.role = role;
      bond.rated = wiz.getRatedForName(rated);
      bond.year = wiz.getYearForName(year);
      bond.pr = wiz.getPRForName(pr);
      bond.network = wiz.getNetworkForName(network);
      bond.setChannelName((chanName == null) ? "" : chanName);
      bond.slotType = slotType;
      bond.timeslots = timeslots;
      bond.keyword = (keyword == null) ? "" : keyword;
      int srchIdx = Arrays.binarySearch(allAgents, bond, AGENT_SORTER);
      if (srchIdx < 0)
      {
        rv = wiz.addAgent(agentMask, title, category, subCategory,
            person, role, rated, year, pr, network, chanName, slotType, timeslots, keyword);
      }
      else
      {
        return (Agent)allAgents[srchIdx];
      }
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }

    long defaultFavoriteStartPadding = Sage.getLong("default_favorite_start_padding", Sage.EMBEDDED ? 5*Sage.MILLIS_PER_MIN : 0);
    long defaultFavoriteStopPadding = Sage.getLong("default_favorite_stop_padding", Sage.EMBEDDED ? 5*Sage.MILLIS_PER_MIN : 0);
    if (defaultFavoriteStartPadding != 0)
    {
      rv.setStartPadding(defaultFavoriteStartPadding);
    }
    if (defaultFavoriteStopPadding != 0)
    {
      rv.setStopPadding(defaultFavoriteStopPadding);
    }

    handleAddedFavorite(rv);

    submitJob(new Object[] { LOVE_JOB, null });
    sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.FAVORITE_ADDED,
        new Object[] { sage.plugin.PluginEventManager.VAR_FAVORITE, rv });
    return rv;
  }

  /**
   * Update the internal Carny state after a favorite has been added (or enabled)
   * @param rv The Favorite that was updated
   */
  private void handleAddedFavorite(Agent rv)
  {
    // LOVESET UPDATE Make the updates to the loveSet that need to be & sync the clients
    // For add, we just add all of the Airings that match this Favorite to the loveAirSet
    List<Airing> airsToAdd = new ArrayList<Airing>();
    long start = Sage.time();
    boolean keywordTest = (rv.agentMask&(Agent.KEYWORD_MASK)) == (Agent.KEYWORD_MASK) &&
        !Sage.getBoolean("use_legacy_keyword_favorites", true);
    if(keywordTest) {
      Show[] shows = wiz.searchShowsByKeyword(rv.getKeyword());
      for (Show show : shows) {
        Airing[] airings = wiz.getAirings(show, 0);
        for(Airing air : airings) {
          if(rv.followsTrend(air,true, null, true)) {
            airsToAdd.add(air);
          }
        }
      }
      start = Sage.time() - start;
      if (Sage.DBG) System.out.println("addFavorite: Time to process lucene search(" + rv.getKeyword() + "): "
          + start + ". added " + airsToAdd.size() + " shows.");
    } else {
      DBObject[] rawAirs = wiz.getRawAccess(Wizard.AIRING_CODE, Wizard.AIRINGS_BY_CT_CODE);
      StringBuffer sbCache = new StringBuffer();
      for (int i = 0; i < rawAirs.length; i++)
      {
        Airing a = (Airing) rawAirs[i];
        if (a != null && rv.followsTrend(a, true, sbCache))
          airsToAdd.add(a);
      }
      start = Sage.time() - start;
      if (Sage.DBG) System.out.println("addFavorite: Time to process rawAirs[" + rawAirs.length + "]: " + start);
    }
    boolean dontScheduleThisAgent = false;
    if (rv.testAgentFlag(Agent.DONT_AUTODELETE_FLAG) &&
        rv.getAgentFlag(Agent.KEEP_AT_MOST_MASK) > 0)
    {
      int fileCount = 0;
      for (int i = 0; i < airsToAdd.size(); i++)
      {
        MediaFile mf = wiz.getFileForAiring(airsToAdd.get(i));
        if (mf != null && mf.isCompleteRecording())
          fileCount++;
      }
      int keepAtMost = rv.getAgentFlag(Agent.KEEP_AT_MOST_MASK);
      if (fileCount >= keepAtMost)
      {
        dontScheduleThisAgent = true;
      }
    }

    if (!airsToAdd.isEmpty())
    {
      synchronized (this)
      {
        loveAirSet.addAll(airsToAdd);
        for (int i = 0; i < airsToAdd.size(); i++)
        {
          // Make these 1.0 in the WP map
          Airing a = airsToAdd.get(i);
          if (!a.isWatchedForSchedulingPurpose() && a.time < Sage.time() + Sage.getLong("scheduling_lookahead", LOOKAHEAD) &&
              a.time >= Sage.time() - Scheduler.SCHEDULING_LOOKBEHIND &&
              a.stationID != 0)
          {
            wpMap.put(a, new Float(1.0f));
            if (!dontScheduleThisAgent)
            {
              mustSeeSet.add(a);
            }
          }
          causeMap.put(a, rv);
        }
        Set<Airing> tempSet = new HashSet<Airing>(Arrays.asList(pots));
        tempSet.addAll(airsToAdd);
        pots = tempSet.toArray(Pooler.EMPTY_AIRING_ARRAY);
      }
      clientSyncAll();
      SchedulerSelector.getInstance().kick(true);
    }
  }

  public Agent updateFavorite(Agent fav, int agentMask, String title, String category, String subCategory,
      Person person, int role, String rated, String year, String pr, String network,
      String chanName, int slotType, int[] timeslots, String keyword)
  {
    if (SageConstants.LITE) return null;
    if (fav == null) return null;
    List<Agent> allFavs = new ArrayList<Agent>();
    Agent oldFav = (Agent) fav.clone();
    Agent bond = (Agent) fav.clone();
    bond.agentMask = agentMask;
    bond.title = wiz.getTitleForName(title);
    bond.category = wiz.getCategoryForName(category);
    bond.subCategory = wiz.getSubCategoryForName(subCategory);
    bond.person = person;
    bond.role = role;
    bond.rated = wiz.getRatedForName(rated);
    bond.year = wiz.getYearForName(year);
    bond.pr = wiz.getPRForName(pr);
    bond.network = wiz.getNetworkForName(network);
    bond.chanName = (chanName == null) ? "" : chanName;
    bond.slotType = slotType;
    bond.timeslots = timeslots;
    bond.keyword = (keyword == null) ? "" : keyword;
    if (AGENT_SORTER.compare(fav, bond) == 0)
      return fav; // no changes
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      DBObject[] allAgents = wiz.getRawAccess(Wizard.AGENT_CODE, Wizard.AGENTS_BY_CARNY_CODE);
      int srchIdx = Arrays.binarySearch(allAgents, bond, AGENT_SORTER);
      if (srchIdx >= 0)
      {
        // Attempt to modify a Favorite to match one that already exists. Do NOT allow this
        // because it violates uniqueness in sorting of AGENTS_BY_CARNY_CODE.
        return (Agent)allAgents[srchIdx];
      }
      if (Sage.DBG) System.out.println("Updating Favorite: " + fav + " to " + bond);
      fav.update(bond);
      wiz.logUpdate(fav, Wizard.AGENT_CODE);
      // IMPORTANT: We also have to resort the index because its dependent on the agentMask
      // for sorting and that's what we may have just changed here!
      wiz.check(Wizard.AGENT_CODE);
      allAgents = wiz.getRawAccess(Wizard.AGENT_CODE, Wizard.AGENTS_BY_CARNY_CODE);
      for (int i = 0; i < allAgents.length; i++)
      {
        Agent currAgent = (Agent) allAgents[i];
        if (currAgent != null && ((currAgent.agentMask & Agent.LOVE_MASK) == Agent.LOVE_MASK))
          allFavs.add(currAgent);
      }
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }
    // LOVESET UPDATE Make the updates to the loveSet that need to be & sync the clients
    ArrayList<Airing> airsThatMayDie = new ArrayList<Airing>();
    ArrayList<Airing> airsThatWillSurvive = new ArrayList<Airing>();
    DBObject[] airs;
    boolean keywordTest = ((oldFav.agentMask&fav.agentMask)&(Agent.KEYWORD_MASK)) == (Agent.KEYWORD_MASK) &&
        !Sage.getBoolean("use_legacy_keyword_favorites", true);
    if(keywordTest) {
      // Slim the haystack for finding needles faster.
      Set<Airing> airingsHaystack = new HashSet<Airing>();
      Show[] shows = wiz.searchShowsByKeyword(fav.getKeyword());
      for (Show show : shows) {
        Airing[] airings = wiz.getAirings(show, 0);
        Collections.addAll(airingsHaystack, airings);
      }
      shows = wiz.searchShowsByKeyword(oldFav.getKeyword());
      for (Show show : shows) {
        Airing[] airings = wiz.getAirings(show, 0);
        Collections.addAll(airingsHaystack, airings);
      }
      airs = airingsHaystack.toArray(Pooler.EMPTY_AIRING_ARRAY);
    } else {
      airs = wiz.getRawAccess(Wizard.AIRING_CODE, Wizard.AIRINGS_BY_CT_CODE);
    }

    StringBuffer sbCache = new StringBuffer();
    for (int i = 0; i < airs.length; i++)
    {
      Airing a = (Airing) airs[i];
      if (a != null)
      {
        if (fav.followsTrend(a, true, sbCache, keywordTest))
          airsThatWillSurvive.add(a);
        else if (oldFav.followsTrend(a, true, sbCache, keywordTest))
        {
          // If there's another favorite that matches this, then we need to hold onto it
          boolean airWasSaved = false;
          for (int j = 0; j < allFavs.size(); j++)
          {
            Agent otherFav = allFavs.get(j);
            if (otherFav.followsTrend(a, false, sbCache))
            {
              airWasSaved = true;
              break;
            }
          }
          if (!airWasSaved)
            airsThatMayDie.add(a);
        }
      }
    }
    boolean dontScheduleThisAgent = false;
    if (fav.testAgentFlag(Agent.DONT_AUTODELETE_FLAG) &&
        fav.getAgentFlag(Agent.KEEP_AT_MOST_MASK) > 0)
    {
      int fileCount = 0;
      for (int i = 0; i < airsThatWillSurvive.size(); i++)
      {
        MediaFile mf = wiz.getFileForAiring(airsThatWillSurvive.get(i));
        if (mf != null && mf.isCompleteRecording())
          fileCount++;
      }
      int keepAtMost = fav.getAgentFlag(Agent.KEEP_AT_MOST_MASK);
      if (fileCount >= keepAtMost)
      {
        dontScheduleThisAgent = true;
      }
    }
    if (!airsThatMayDie.isEmpty() || !airsThatWillSurvive.isEmpty())
    {
      airsThatMayDie.removeAll(airsThatWillSurvive);
      synchronized (this)
      {
        synchronized (loveAirSet)
        {
          loveAirSet.removeAll(airsThatMayDie);
          loveAirSet.addAll(airsThatWillSurvive);
        }
        for (int i = 0; i < airsThatWillSurvive.size(); i++)
        {
          // Make these 1.0 in the WP map
          Airing a = airsThatWillSurvive.get(i);
          if (!a.isWatchedForSchedulingPurpose() && a.time < Sage.time() + Sage.getLong("scheduling_lookahead", LOOKAHEAD) &&
              a.time >= Sage.time() - Scheduler.SCHEDULING_LOOKBEHIND &&
              a.stationID != 0)
          {
            wpMap.put(a, 1.0f);
            if (!dontScheduleThisAgent)
            {
              mustSeeSet.add(a);
            }
          }
          causeMap.put(a, fav);
        }
        mustSeeSet.removeAll(airsThatMayDie);
        /*
         * NOTE: We're clearing the WP and the cause for any Airing that was removed from Favorite status.
         * This is a little more harsh than we need to be, but this'll get fixed up on the next Carny round.
         * The alternative involves a fair amount of extra processing here that we don't really want to do
         * UPDATE: Narflex - 9/18/08 - I had this delete a Favorite of mine that I wanted and realized a better
         * way to do this. The WP should be set to 0.9 for these guys since they were just a Fav and should likely be
         * retained. Then we'll clean that up on the next Carny round. Better than letting them drop off into
         * oblivion temporarily and risk a delete cycle kicking in that destroys the content.
         */
        Float oldFavWP = 0.9f;
        synchronized (wpMap)
        {
          for (Airing air : airsThatMayDie) {
            wpMap.put(air, oldFavWP);
          }
        }

        //causeMap.keySet().removeAll(airsThatMayDie);
        //wpMap.keySet().removeAll(airsThatMayDie);
        Set<Airing> tempSet = new HashSet<Airing>(Arrays.asList(pots));
        tempSet.addAll(airsThatWillSurvive);
        //tempSet.removeAll(airsThatMayDie);
        pots = tempSet.toArray(Pooler.EMPTY_AIRING_ARRAY);
      }
      clientSyncAll();
      SchedulerSelector.getInstance().kick(true);
    }
    submitJob(new Object[] { LOVE_JOB, null });
    sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.FAVORITE_MODIFIED,
        new Object[] { sage.plugin.PluginEventManager.VAR_FAVORITE, fav });
    return fav;
  }
  public void removeFavorite(Agent fav)
  {
    if (SageConstants.LITE) return;
    if (Sage.DBG) System.out.println("Removing Favorite: " + fav);
    List<Agent> allFavs = new ArrayList<Agent>();
    try {
      wiz.acquireWriteLock(Wizard.AGENT_CODE);
      wiz.removeAgent(fav);
      DBObject[] allAgents = wiz.getRawAccess(Wizard.AGENT_CODE, Wizard.AGENTS_BY_CARNY_CODE);
      for (int i = 0; i < allAgents.length; i++)
      {
        Agent currAgent = (Agent) allAgents[i];
        if (currAgent != null && ((currAgent.agentMask & Agent.LOVE_MASK) == Agent.LOVE_MASK))
          allFavs.add(currAgent);
      }
    } finally {
      wiz.releaseWriteLock(Wizard.AGENT_CODE);
    }

    handleRemovedFavorite(fav, allFavs);

    submitJob(new Object[] { LOVE_JOB, null });
    sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.FAVORITE_REMOVED,
        new Object[] { sage.plugin.PluginEventManager.VAR_FAVORITE, fav });
  }

  /**
   * Update the internal Carny state after a favorite has been removed (or disabled)
   * @param fav The Favorite that was removed
   * @param allFavs The collection of all Favorites
   */
  private void handleRemovedFavorite(Agent fav, List<Agent> allFavs)
  {
    // LOVESET UPDATE Make the updates to the loveSet that need to be & sync the clients
    List<Airing> airsThatMayDie = new ArrayList<Airing>();
    DBObject[] airs;
    boolean keywordTest = (fav.agentMask & (Agent.KEYWORD_MASK)) == (Agent.KEYWORD_MASK) &&
        !Sage.getBoolean("use_legacy_keyword_favorites", true);
    if(keywordTest) {
      // Slim the haystack for finding needles faster.
      ArrayList<Airing> airingsHaystack = new ArrayList<Airing>();
      Show[] shows = wiz.searchShowsByKeyword(fav.getKeyword());
      for (Show show : shows) {
        Airing[] airings = wiz.getAirings(show, 0);
        Collections.addAll(airingsHaystack, airings);
      }
      airs = airingsHaystack.toArray(Pooler.EMPTY_AIRING_ARRAY);
    } else {
      airs = wiz.getRawAccess(Wizard.AIRING_CODE, Wizard.AIRINGS_BY_CT_CODE);
    }
    StringBuffer sbCache = new StringBuffer();
    boolean resyncAll = false;
    for (int i = 0; i < airs.length; i++)
    {
      Airing a = (Airing)airs[i];
      if (a != null && fav.followsTrend(a, true, sbCache, keywordTest))
      {
        // If there's another favorite that matches this, then we need to hold onto it
        boolean airWasSaved = false;
        for (int j = 0; j < allFavs.size(); j++)
        {
          Agent otherFav = allFavs.get(j);
          if (otherFav.followsTrend(a, false, sbCache))
          {
            // Also swap out the cause agent
            resyncAll = true;
            synchronized (this)
            {
              causeMap.put(a, otherFav);
            }
            airWasSaved = true;
            break;
          }
        }
        if (!airWasSaved)
          airsThatMayDie.add(a);
      }
    }
    if (!airsThatMayDie.isEmpty())
    {
      synchronized (this)
      {
        loveAirSet.removeAll(airsThatMayDie);
        mustSeeSet.removeAll(airsThatMayDie);
        /*
         * NOTE: We're clearing the WP and the cause for any Airing that was removed from Favorite status.
         * This is a little more harsh than we need to be, but this'll get fixed up on the next Carny round.
         * The alternative involves a fair amount of extra processing here that we don't really want to do
         */
        synchronized (causeMap)
        {
          causeMap.keySet().removeAll(airsThatMayDie);
        }
        synchronized (wpMap)
        {
          wpMap.keySet().removeAll(airsThatMayDie);
        }
        Set<Airing> tempSet = new HashSet<Airing>(Arrays.asList(pots));
        tempSet.removeAll(airsThatMayDie);
        pots = tempSet.toArray(Pooler.EMPTY_AIRING_ARRAY);
      }
      clientSyncAll();
      SchedulerSelector.getInstance().kick(true);
    }
    else if (resyncAll)
    {
      clientSyncAll();
      SchedulerSelector.getInstance().kick(true);
    }

    clientSyncLoves();
  }

  /**
   * Set the enabled/disabled state of a favorite
   * @param fav The Favorite to enable/disable
   * @param enabled true if the given favorite should be enabled, false if it should be disabled
   */
  public void enableFavorite(Agent fav, boolean enabled)
  {
      //If the DISabled flag is equal to the ENabled parameter, then the call to this function should
      //  change the state of the given Agent, otherwise nothing changes
      if(fav.testAgentFlag(Agent.DISABLED_FLAG) == enabled)
      {
          //first update the agent flag
          setAgentFlags(fav, Agent.DISABLED_FLAG, enabled?0:Agent.DISABLED_FLAG);

          //Next update the Carny internal state
          if(enabled) {
              handleAddedFavorite(fav);
          } else {
              handleRemovedFavorite(fav, Arrays.asList(Wizard.getInstance().getFavorites()));
          }

          submitJob(new Object[] { LOVE_JOB, null });
          sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.FAVORITE_MODIFIED,
              new Object[] { sage.plugin.PluginEventManager.VAR_FAVORITE, fav });
      }
  }

  private void clientSyncAll()
  {
    synchronized (listeners)
    {
      SageTV.incrementQuanta();
      // In case any get removed along the way
      ProfilingListener[] listData = listeners.toArray(new ProfilingListener[0]);
      for (int i = 0; i < listData.length; i++)
      {
        if (!listData[i].updateLoves(loveAirSet)) // we used to not do this, but it does need to be synced 2/4/2004
          continue;
        if (!listData[i].updateWPMap(wpMap))
          continue;
        if (!listData[i].updateCauseMap(causeMap))
          continue;
        if (!listData[i].updateMustSees(mustSeeSet))
          continue;
      }
    }
  }

  private void clientSyncLoves()
  {
    synchronized (listeners)
    {
      SageTV.incrementQuanta();
      // In case any get removed along the way
      ProfilingListener[] listData = listeners.toArray(new ProfilingListener[0]);
      for (int i = 0; i < listData.length; i++)
      {
        listData[i].updateLoves(loveAirSet);
      }
    }
  }

  synchronized boolean fullClientUpdate(ProfilingListener updateMe)
  {
    if (!updateMe.updateCauseMap(causeMap))
      return false;
    if (!updateMe.updateWPMap(wpMap))
      return false;
    if (!updateMe.updateMustSees(mustSeeSet))
      return false;
    if (!updateMe.updateLoves(loveAirSet))
      return false;
    return true;
  }

  public void addDontLike(Airing air, boolean manual)
  {
    submitWasteJob(air, true, manual);
    SchedulerSelector.getInstance().kick(true);
  }
  public void removeDontLike(Airing air)
  {
    submitWasteJob(air, false, true);
    SchedulerSelector.getInstance().kick(true);
  }
  private void submitWasteJob(Airing air, boolean doWaste, boolean manual)
  {
    // Don't track Wasted for non-TV content
    if (SageConstants.LITE || !air.isTV()) return;
    if (doWaste)
    {
      wiz.addWasted(air, manual);
      submitJob(new Object[] { WASTED_JOB, air });
    }
    else if (wiz.getWastedForAiring(air) != null)
      wiz.removeWasted(wiz.getWastedForAiring(air));
    else {
      // Just clear it for the Show
      Show s = air.getShow();
      if (s != null) {
        s.setDontLike(false);
      }
    }
  }

  void submitJob(Object[] jobData)
  {
    synchronized (jobs)
    {
      jobs.addElement(jobData);
      jobs.notifyAll();
    }
  }

  public void kick()
  {
    synchronized (jobs)
    {
      jobs.addElement(new Object[] { STD_JOB, null });
      jobs.notifyAll();
    }
  }
  void kickHard()
  {
    synchronized (jobs)
    {
      jobs.addElement(new Object[] { REQUIRED_JOB, null });
      jobs.notifyAll();
    }
  }

  private void applyWatchData(Airing watchAir)
  {
    // First we try to find any Agents that apply to this already.
    // This is how we can know if there's one with a matching template already,
    // and be able to modify the information the agent is based on.
    //DBObject[] allAgents = wiz.getRawAccess(Wizard.AGENT_CODE, Wizard.AGENTS_BY_CARNY_CODE);
    //System.out.println("AGENTS=" + Arrays.asList(allAgents));
    Show s = watchAir.getShow();
    // Don't do profiling on non-TV watches
    if (s == null || !watchAir.isTV()) return;

    // Look at each type, and then see if each trend for that
    // type is satisfied.
    int personIdx = 0;
    Agent bond = new Agent(0);
    for (int trendPos = 0; trendPos < trends.length; trendPos++)
    {
      int trend = trends[trendPos];
      bond.agentMask = trend;
      bond.title = bond.network = bond.category = bond.subCategory =
          bond.rated = bond.pr = bond.year = null;
      bond.person = null;
      bond.chanName = "";
      if ((trend & Agent.TITLE_MASK) == Agent.TITLE_MASK)
      {
        // Don't do title tracking for movies
        if (s.title == null || s.getExternalID().startsWith("MV"))
          continue;
        bond.title = s.title;
      }
      if ((trend & Agent.FIRSTRUN_MASK) == Agent.FIRSTRUN_MASK && !watchAir.isFirstRun())
        continue;
      if ((trend & Agent.RERUN_MASK) == Agent.RERUN_MASK && watchAir.isFirstRun())
        continue;
      if ((trend & Agent.CHANNEL_MASK) == Agent.CHANNEL_MASK)
        bond.chanName = watchAir.getChannelName();
      if ((trend & Agent.CATEGORY_MASK) == Agent.CATEGORY_MASK)
      {
        if (s.categories.length == 0)
          continue;
        bond.category = s.categories[0];
        bond.subCategory = (s.categories.length > 1 ? s.categories[1] : null);
      }
      if ((trend & Agent.NETWORK_MASK) == Agent.NETWORK_MASK)
      {
        if (watchAir.getChannel() == null || watchAir.getChannel().network == null)
          continue;
        bond.network = watchAir.getChannel().network;
      }
      if ((trend & Agent.ACTOR_MASK) == Agent.ACTOR_MASK)
      {
        if (s.people.length == 0)
          continue;
        bond.person = s.people[personIdx];
        personIdx++;
        if (personIdx < s.people.length)
          trendPos--;
        else
          personIdx = 0;
      }
      if ((trend & Agent.YEAR_MASK) == Agent.YEAR_MASK)
      {
        if (s.year == null)
          continue;
        bond.year = s.year;
      }
      if ((trend & Agent.PR_MASK) == Agent.PR_MASK)
      {
        if (s.pr == null)
          continue;
        bond.pr = s.pr;
      }
      if ((trend & Agent.RATED_MASK) == Agent.RATED_MASK)
      {
        if (s.rated == null)
          continue;
        bond.rated = s.rated;
      }
      if ((trend & Agent.FULLSLOT_MASK) == Agent.FULLSLOT_MASK)
      {
        bond.slotType = BigBrother.FULL_ALIGN;
        bond.timeslots = new int[] { Profiler.determineSlots(watchAir)[0] };
      }
      else if ((trend & Agent.DAYSLOT_MASK) == Agent.DAYSLOT_MASK)
      {
        bond.slotType = BigBrother.DAY_ALIGN;
        bond.timeslots = new int[] { Profiler.determineSlots(watchAir)[0] };
      }
      else if ((trend & Agent.TIMESLOT_MASK) == Agent.TIMESLOT_MASK)
      {
        bond.slotType = BigBrother.TIME_ALIGN;
        bond.timeslots = new int[] { Profiler.determineSlots(watchAir)[0] };
      }

      try {
        wiz.acquireWriteLock(Wizard.AGENT_CODE);
        int srchIdx = Arrays.binarySearch(
            wiz.getRawAccess(Wizard.AGENT_CODE, Wizard.AGENTS_BY_CARNY_CODE),
            bond, AGENT_SORTER);
        if (srchIdx < 0)
        {
          wiz.addAgent(trend, (bond.title == null) ? null : bond.title.name,
              (bond.category == null) ? null : bond.category.name,
              (bond.subCategory == null) ? null : bond.subCategory.name,
              bond.person,
              (bond.person == null) ? 0 : Show.ALL_ROLES,
              (bond.rated == null) ? null : bond.rated.name,
              (bond.year == null) ? null : bond.year.name,
              (bond.pr == null) ? null : bond.pr.name,
              (bond.network == null) ? null : bond.network.name,
              bond.chanName, bond.slotType, bond.timeslots, bond.keyword);
        }
      } finally {
        wiz.releaseWriteLock(Wizard.AGENT_CODE);
      }
    }
  }

  private void applyWasteData(Airing wasteAir)
  {
    // First we try to find any Agents that apply to this already.
    // This is how we can know if there's one with a matching template already,
    // and be able to modify the information the agent is based on.
    Show s = wasteAir.getShow();
    // We don't track 'wasted' objects on embedded so disable that here
    if (s == null || !wasteAir.isTV() || Sage.EMBEDDED) return;
    Stringer stit = s.title;
    Agent[] allAgents = wiz.getAgents();
    StringBuffer sbCache = new StringBuffer();
    for (int i = 0; i < allAgents.length; i++)
    {
      if (allAgents[i] == null) continue;

      Agent bond = allAgents[i];
      if ((bond.agentMask & Agent.TITLE_MASK) == Agent.TITLE_MASK &&
          bond.title == stit && bond.followsTrend(wasteAir, false, sbCache))
      {
        // There's already a tracker on this title, we're done
        return;
      }
    }

    wiz.addAgent(Agent.TITLE_MASK | (wasteAir.isFirstRun() ? Agent.FIRSTRUN_MASK : Agent.RERUN_MASK), stit.name,
        null, null, null, 0, null, null, null, null, null,
        0, null, null);
  }

  // We first have all of the agents tell us what they will use if it's available. This way we don't
  // waste time gathering mappings that nothing is actually going to use.
  private Set<Integer> getAgentIntrests(List<Agent> agents)
  {
    Set<Integer> interests = new HashSet<>();
    for (int i = 0, agentsLength = agents.size(); i < agentsLength; i++)
    {
      for (int hash : agents.get(i).getHashes())
      {
        interests.add(hash);
      }
    }
    // This is a special hash code for airings that the agent might not know it needs and it is
    // checked on all agents just in case.
    interests.add(0);
    return interests;
  }

  private void addIfNeeded(Integer hash, Airing airing, Map<Integer, List<Airing>> buildMap, Collection<Integer> interested)
  {
    if (!interested.contains(hash))
      return;
    List<Airing> mappedAirings = buildMap.get(hash);
    // We are using an array list to keep things in order and it allows us to use a binary search
    // later when we iterate over this data at a high frequency.
    if (mappedAirings == null)
    {
      mappedAirings = new ArrayList<>();
      buildMap.put(hash, mappedAirings);
      mappedAirings.add(airing);
    }
    else
    {
      // If we might be adding a duplicate, it will be the last entry because we call this multiple
      // times for the same airing and it might have redundant hashes.
      if (mappedAirings.get(mappedAirings.size() - 1) != airing)
        mappedAirings.add(airing);
    }
  }

  private Map<Integer, Airing[]> buildMap(Collection<Airing> airings, Collection<Integer> interested, Map<Integer, Airing[]> mergeMap)
  {
    Map<Integer, List<Airing>> buildMap = new HashMap<>();
    for (Airing airing : airings)
    {
      Show show = airing.getShow();
      int currentHash = show.title == null ? 0 : show.title.ignoreCaseHash;
      addIfNeeded(currentHash, airing, buildMap, interested);

      currentHash = airing.isFirstRun() ? Agent.FIRSTRUN_MASK : Agent.RERUN_MASK;
      addIfNeeded(currentHash, airing, buildMap, interested);

      for (Person person : show.people)
      {
        currentHash = person.ignoreCaseHash;
        addIfNeeded(currentHash, airing, buildMap, interested);
      }

      if (show.categories != null)
      {
        for (Stringer category : show.categories)
        {
          currentHash = category.ignoreCaseHash;
          addIfNeeded(currentHash, airing, buildMap, interested);
        }
      }

      Channel c = airing.getChannel();
      if (c != null)
      {
        if (c.name != null)
        {
          currentHash = c.name.hashCode();
          addIfNeeded(currentHash, airing, buildMap, interested);
        }

        if (c.network != null)
        {
          currentHash = c.network.ignoreCaseHash;
          addIfNeeded(currentHash, airing, buildMap, interested);
        }
      }

      if (show.rated != null)
      {
        currentHash = show.rated.ignoreCaseHash;
        addIfNeeded(currentHash, airing, buildMap, interested);
      }

      if (show.year != null)
      {
        currentHash = show.year.ignoreCaseHash;
        addIfNeeded(currentHash, airing, buildMap, interested);
      }

      if (show.pr != null)
      {
        currentHash = show.pr.ignoreCaseHash;
        addIfNeeded(currentHash, airing, buildMap, interested);
      }

      // Agents that use the slotType and timeslots need to process all airs because there isn't
      // currently an efficient way to map that data for quick lookups.

      // Agents that use keywords need to process all airs because there are we have not come up
      // with and efficient way to look them up.
    }

    if (mergeMap != null)
    {
      for (Map.Entry<Integer, Airing[]> entry : mergeMap.entrySet())
      {
        List<Airing> mappedAirings = buildMap.get(entry.getKey());
        if (mappedAirings == null)
          buildMap.put(entry.getKey(), (mappedAirings = new ArrayList<Airing>()));
        Set<Airing> dedupe = new HashSet<>(Arrays.asList(entry.getValue()));
        dedupe.addAll(mappedAirings);
        mappedAirings.clear();
        mappedAirings.addAll(dedupe);
      }
    }

    // We could have this array around for a long time, so let's make sure we have the smallest
    // possible footprint.
    Map<Integer, Airing[]> returnMap = new HashMap<>();
    for (Map.Entry<Integer, List<Airing>> entry : buildMap.entrySet())
    {
      Airing[] finalAirings = entry.getValue().toArray(Pooler.EMPTY_AIRING_ARRAY);
      // Sort by ID. Technically the list should already be in order, but this
      // ensures we are not mistaken.
      Arrays.sort(finalAirings, DBObject.ID_COMPARATOR);
      returnMap.put(entry.getKey(), finalAirings);
    }
    return returnMap;
  }

  private void stdProcessing()
  {
    String lastMessage = null;
    if (!doneInit)
    {
      lastMessage = Sage.rez("Module_Init_Progress", new Object[] { Sage.rez("Profiler"), new Double(0)});
      Sage.setSplashText(lastMessage);
    }

    boolean disableCarnyMaps = Sage.getBoolean(prefs + DISABLE_CARNY_MAPS, false);
    List<Future<Boolean>> agentWorkerFutures = new ArrayList<>();
    // This tracks when we started trying to compile the profiler info so we don't keep aborting attempts forever if it takes
    // a long time to figure out
    if (lastCycleCompleteTime >= cycleStartTime)
      cycleStartTime = Sage.eventTime();
    // Go through each Agent and run their think() process.
    Map<Airing, Float> newWPMap = Collections.synchronizedMap(new HashMap<Airing, Float>());
    Map<Airing, Agent> newCauseMap = Collections.synchronizedMap(new HashMap<Airing, Agent>());
    Set<Airing> airSet = Collections.synchronizedSet(new HashSet<Airing>());
    Set<Airing> newMustSeeSet = Collections.synchronizedSet(new HashSet<Airing>());
    Set<Airing> blackBalled = Collections.synchronizedSet(new HashSet<Airing>());

    DBObject[] allAgents = wiz.getRawAccess(Wizard.AGENT_CODE, (byte) 0);
    Set<Airing> newLoveAirSet = Collections.synchronizedSet(new HashSet<Airing>());

    // We don't track music at all (that used to be in Agent.followsTrend)

    synchronized (this)
    {
      // Clear this out since we're getting fresh airings from the DB now
      swapMap.clear();
    }
    DBObject[] rawAirs = wiz.getRawAccess(Wizard.AIRING_CODE, Wizard.AIRINGS_BY_CT_CODE);
    Set<Airing> airset = new HashSet<Airing>();
    List<Airing> remAirSet = new ArrayList<Airing>();

    long currLookahead = Sage.getLong("scheduling_lookahead", LOOKAHEAD);
    int testMask = DBObject.MEDIA_MASK_TV;
    long currTime = Sage.time();
    for (int i = 0; i < rawAirs.length; i++)
    {
      Airing a = (Airing) rawAirs[i];
      // Only use TV Airings in this calculation
      if (a == null || !a.hasMediaMaskAny(testMask)) continue;
      if (a.time < currTime + currLookahead &&
          a.time >= currTime - Scheduler.SCHEDULING_LOOKBEHIND && a.isTV())
      {
        airset.add(a);
      }
      else
      {
        remAirSet.add(a);
      }
    }

    // We also need to be sure we analyze all of the files
    MediaFile[] mfs = wiz.getFiles();
    for (int i = 0; i < mfs.length; i++)
    {
      if (!mfs[i].archive && mfs[i].isTV())
        airset.add(mfs[i].getContentAiring());
    }

    // Add all of the work into a shared queue so we don't end up giving one thread a lot more work
    // over another.
    int submittedAgents;
    List<Agent> mapAgents = disableCarnyMaps ? null : new ArrayList<Agent>(allAgents.length);
    if (!doneInit && Sage.getBoolean(LIMITED_CARNY_INIT, true))
    {
      for (int i = 0; i < allAgents.length; i++)
      {
        Agent currAgent = (Agent) allAgents[i];
        // This is an inexpensive check against two flags so we aren't adding agents to the queue
        // that we already know shouldn't be there.
        if (currAgent != null && !currAgent.testAgentFlag(Agent.DISABLED_FLAG) && currAgent.isFavorite())
        {
          agentWorkQueue.add(currAgent);
          if (!disableCarnyMaps)
            mapAgents.add(currAgent);
        }
      }
      submittedAgents = agentWorkQueue.size();
    }
    else
    {
      for (int i = 0; i < allAgents.length; i++)
      {
        Agent currAgent = (Agent) allAgents[i];
        // This is an inexpensive check against a flag so we aren't adding agents to the queue that
        // we already know shouldn't be there.
        if (currAgent != null && !currAgent.testAgentFlag(Agent.DISABLED_FLAG))
        {
          agentWorkQueue.add(currAgent);
          if (!disableCarnyMaps)
            mapAgents.add(currAgent);
        }
      }
      submittedAgents = agentWorkQueue.size();
    }

    Map<Integer, Airing[]> allAirsMap;
    Map<Integer, Airing[]> remAirsMap;
    Map<Integer, Airing[]> watchAirsMap;
    Map<Integer, Airing[]> wastedAirsMap;

    Airing[] allAirs;
    Airing[] remAirs;
    Airing[] watchAirs;
    Airing[] wastedAirs;

    if (disableCarnyMaps)
    {
      allAirsMap = null;
      remAirsMap = null;
      watchAirsMap = null;
      wastedAirsMap = null;

      allAirs = airset.toArray(Pooler.EMPTY_AIRING_ARRAY);
      remAirs = remAirSet.toArray(Pooler.EMPTY_AIRING_ARRAY);
      watchAirs = null;
      wastedAirs = null;
    }
    else
    {
      Set<Integer> agentHashes = getAgentIntrests(mapAgents);

      DBObject[] watches = wiz.getRawAccess(Wizard.WATCH_CODE, (byte) 0);
      List<Airing> fullWatchAirsList = new ArrayList<>();
      for (int i = 0; i < watches.length; i++)
      {
        Watched currWatch = (Watched) watches[i];
        if (currWatch != null && BigBrother.isFullWatch(currWatch))
        {
          fullWatchAirsList.add(currWatch.getAiring());
        }
      }

      DBObject[] waste = wiz.getRawAccess(Wizard.WASTED_CODE, (byte) 0);
      List<Airing> wastedAirsList = new ArrayList<>();
      for (int i = 0; i < waste.length; i++)
      {
        Wasted currWaste = (Wasted) waste[i];
        if (currWaste != null)
        {
          wastedAirsList.add(currWaste.getAiring());
        }
      }

      allAirsMap = buildMap(airset, agentHashes, null);
      remAirsMap = buildMap(remAirSet, agentHashes, null);
      watchAirsMap = buildMap(fullWatchAirsList, agentHashes, null);
      wastedAirsMap = buildMap(wastedAirsList, agentHashes, null);

      allAirs = airset.toArray(Pooler.EMPTY_AIRING_ARRAY);
      remAirs = remAirSet.toArray(Pooler.EMPTY_AIRING_ARRAY);
      watchAirs = fullWatchAirsList.toArray(Pooler.EMPTY_AIRING_ARRAY);
      wastedAirs = wastedAirsList.toArray(Pooler.EMPTY_AIRING_ARRAY);
    }

    List<Agent> traitors = Collections.synchronizedList(new ArrayList<Agent>());
    Set<Airing> watchedPotsToClear = Collections.synchronizedSet(new HashSet<Airing>());
    if (Sage.DBG) System.out.println("CARNY Processing " + allAgents.length + " Agents & " + allAirs.length + " Airs");
    boolean controlCPUUsage = doneInit && allAgents.length > 50 && Sage.getBoolean("control_profiler_cpu_usage", true);

    // If we don't have any agents, there's no point is spinning up worker threads. Also some users
    // might have servers with a lot of cores (> 8), so if we don't have a lot of agents, we will
    // not try to spread the load across every core because it's just a waste of resources. We are
    // arbitrarily selecting 75 agents per core. The number could be higher, but on lower powered
    // machines this will make a more meaningful difference on startup.
    int totalThreads = Sage.getInt(LIMITED_CARNY_THREADS, 0);
    if (totalThreads <= 0)
    {
      totalThreads = submittedAgents == 0 ? 0 :
        Math.min(PROCESSOR_COUNT, Math.max(1, submittedAgents / 75));
    }
    // This is an unlikely number that will not break things like some crazy number someone might
    // set or an bad value returned for the processor count. There is known contention between the
    // threads since they all want to modify several common objects, so at some point there will be
    // diminishing returns. This could be an argument for concurrent objects, but nothing is
    // iterating the collections/maps while they are being modified and in real world testing the
    // contention does not cause any measurable performance loss.
    if (totalThreads > 64)
      totalThreads = 64;
    // Start/resume all of the agent worker threads.
    for (int i = 0; i < totalThreads; i++)
    {
      Callable<Boolean> newJob = new CarnyAgentWorker(controlCPUUsage, totalThreads, doneInit,
        allAirs, remAirs, watchAirs, wastedAirs, allAirsMap, remAirsMap, watchAirsMap, wastedAirsMap, traitors,
        newLoveAirSet, blackBalled, airSet, newWPMap, newCauseMap, watchedPotsToClear, newMustSeeSet);
      agentWorkerFutures.add(agentWorkers.submit(newJob));
    }

    boolean workCanceled = false;
    for (int i = 0; i < agentWorkerFutures.size(); i++)
    {
      Future<Boolean> future = agentWorkerFutures.get(i);
      try
      {
        Boolean keepRunning;
        try
        {
          keepRunning = doneInit ? future.get(1, TimeUnit.MINUTES) : future.get(250, TimeUnit.MILLISECONDS);
          if (keepRunning == null)
            keepRunning = false;
        }
        catch (TimeoutException e)
        {
          // Do not proceed to the next future since we are only breaking out of waiting for it to
          // complete to update the logged progress. Otherwise we do not expect to ever find
          // ourselves in this block.
          i--;
          keepRunning = true;
        }

        int jobsRemoved = submittedAgents - agentWorkQueue.size();

        if (!doneInit)
        {
          if (jobsRemoved == submittedAgents && (agentWorkerFutures.size() - 1) != i)
            continue;
          String newSplashMsg = Sage.rez("Module_Init_Progress",
            new Object[]{Sage.rez("Profiler"), new Double((jobsRemoved * 1.0) / submittedAgents)});
          if (lastMessage == null || !newSplashMsg.equals(lastMessage))
          {
            Sage.setSplashText(newSplashMsg);
          }
          lastMessage = newSplashMsg;
        }
        else if (keepRunning && !workCanceled)
        {
          if (jobsRemoved == submittedAgents && (agentWorkerFutures.size() - 1) != i)
            continue;
          // This isn't a completely accurate statement because removing an agent from the queue
          // doesn't mean we also finished processing the agent at the same time, but it is close
          // enough.
          if (Sage.DBG) System.out.println(
            "Carny agent workers processed " + jobsRemoved + " of " + submittedAgents + " active agents.");
        }

        // If a future returns false, that means we have a new job and need to stop all processing.
        // That means we will likely have excess jobs left in the queue that need to be cleared out.
        // It is ok to do this regardless of if other agent workers are still running because they
        // all need to stop processing anyway.
        if (!keepRunning)
        {
          System.out.println("Carny agent worker returned canceled.");
          agentWorkQueue.clear();
          workCanceled = true;
        }
      }
      catch (Exception e)
      {
        // If we create any exceptions, everything is now invalid, so we need to remove the rest of
        // the agents. We still need to wait for all of the threads to return or we will create a
        // race condition and in this case that would be very bad.
        agentWorkQueue.clear();
        workCanceled = true;
        if (!(e instanceof InterruptedException) && Sage.DBG)
        {
          System.out.println("Carny created an exception while processing: " + e.getMessage());
          e.printStackTrace(System.out);
          Throwable innerException = e.getCause();
          if (innerException != null)
            innerException.printStackTrace(System.out);
        }
      }
    }

    if (workCanceled)
      return;

    synchronized (this)
    {
      for (Airing oldAir : swapMap.keySet())
      {
        if (newLoveAirSet.contains(oldAir))
        {
          newLoveAirSet.remove(oldAir);
          newLoveAirSet.add(swapMap.get(oldAir));
        }
      }
      loveAirSet = newLoveAirSet;
    }

    // Remove any blackballs that are favorites now; unless they're marked don't like
    blackBalled.removeAll(newMustSeeSet);

    // Clear out all of the negative energy, this includes marked waste
    Wasted[] stoners = wiz.getWasted();

    newWPMap.keySet().removeAll(watchedPotsToClear);

    if (Sage.DBG) System.out.println("CARNY Negative Energy Size: " + (blackBalled.size() + stoners.length));
    for (Airing badAir : blackBalled)
    {
      newWPMap.remove(badAir);
      newCauseMap.remove(badAir);
      newMustSeeSet.remove(badAir);
      airSet.remove(badAir);
    }

    for (int i = 0; i < stoners.length; i++)
    {
      Airing badAir = stoners[i].getAiring();
      newWPMap.remove(badAir);
      newCauseMap.remove(badAir);
      newMustSeeSet.remove(badAir);
      airSet.remove(badAir);
    }

    if (Sage.DBG) System.out.println("CARNY Traitors:" + traitors);
    for (int i = 0; i < traitors.size(); i++)
      wiz.removeAgent(traitors.get(i));

    synchronized (this)
    {
      for (Airing oldAir : swapMap.keySet())
      {
        Airing newAir = swapMap.get(oldAir);
        if (newWPMap.containsKey(oldAir))
          newWPMap.put(newAir, newWPMap.remove(oldAir));
        if (airSet.contains(oldAir))
        {
          airSet.remove(oldAir);
          airSet.add(newAir);
        }
        if (newMustSeeSet.contains(oldAir))
        {
          newMustSeeSet.remove(oldAir);
          newMustSeeSet.add(newAir);
        }
        if (newCauseMap.containsKey(oldAir))
          newCauseMap.put(newAir, newCauseMap.remove(oldAir));
      }
      wpMap = newWPMap;
      causeMap = newCauseMap;
      pots = airSet.toArray(Pooler.EMPTY_AIRING_ARRAY);
      mustSeeSet = newMustSeeSet;
    }
    // This is when we need to propagate the change to all of our
    // clients
    clientSyncAll();

    prepped = true;
    lastCycleCompleteTime = Sage.eventTime();

    if (doneInit)
      SchedulerSelector.getInstance().kick(false);
  }

  public int getWatchCount() { return globalWatchCount; }

  public synchronized float getWP(Airing air)
  {
    Float f = wpMap.get(air);
    if (f == null)
      return MIN_WP;
    else
      return Math.max(f, MIN_WP);
  }

  // Returns false iff one is a Favorite and the other isn't, or both are favorites with different causes
  public synchronized boolean areSameFavorite(Airing a1, Airing a2)
  {
    Agent b1 = causeMap.get(a1);
    Agent b2 = causeMap.get(a2);
    if (b1 == b2) return true;
    if (b1 != null && b1.isFavorite())
    {
      // The favorite applies for both. One of them may not be in the cause map because
      // the cause map is limited by the scheduling lookahead. But then we looking further out
      // in the schedule to conflict resolution we can end up calling this test so we need to check this way too.
      return b1.followsTrend(a2, false, null);
    }
    if (b2 != null && b2.isFavorite())
      return b2.followsTrend(a1, false, null);

    // Neither agent is a favorite; so they are both from the null favorite which is true
    return true;
  }

  public synchronized String getCause(Airing air)
  {
    Agent bond = causeMap.get(air);
    if (bond == null) return null;
    else return bond.getCause();
  }

  public synchronized Agent getCauseAgent(Airing air)
  {
    return causeMap.get(air);
  }

  synchronized Airing doBattle(Airing air1, Airing air2)
  {
    Agent agent1 = getCauseAgent(air1);
    Agent agent2 = getCauseAgent(air2);
    if (agent1 == null && agent2 == null) return null;
    if (agent2 == null && agent1 != null)
      return air1;
    if (agent1 == null && agent2 != null)
      return air2;
    Agent secretWinner = familyFeud(agent1, agent2, new HashSet<Agent>());
    if (secretWinner != null)
      return (secretWinner == agent1) ? air1 : air2;
    return null;
  }

  synchronized Agent doBattle(Agent a1, Agent a2)
  {
    return familyFeud(a1, a2, new HashSet<Agent>());
  }

  private Agent familyFeud(Agent agent1, Agent agent2, Set<Agent> fightHistory)
  {
    if (!fightHistory.add(agent1)) return null;
    for (int i = 0; i < agent1.weakAgents.length; i++)
    {
      if (agent1.weakAgents[i] == agent2.id)
        return agent1;
      Agent babyAgent = wiz.getAgentForID(agent1.weakAgents[i]);
      if (babyAgent != null)
      {
        Agent babyFight = familyFeud(babyAgent, agent2, fightHistory);
        if (babyFight == babyAgent)
          return agent1;
      }
    }
    if (!fightHistory.add(agent2)) return null;
    for (int i = 0; i < agent2.weakAgents.length; i++)
    {
      if (agent2.weakAgents[i] == agent1.id)
        return agent2;
      Agent babyAgent = wiz.getAgentForID(agent2.weakAgents[i]);
      if (babyAgent != null)
      {
        Agent babyFight = familyFeud(babyAgent, agent1, fightHistory);
        if (babyFight == babyAgent)
          return agent2;
      }
    }
    return null;
  }

  private static final Comparator<Agent> agentCreateTimeSorter = new Comparator<Agent>()
  {
    public int compare(Agent a1, Agent a2)
    {
      long createDiff = (a1 == null ? Long.MAX_VALUE : a1.createTime) -
          (a2 == null ? Long.MAX_VALUE : a2.createTime);
      if (createDiff < 0)
        return -1;
      else if (createDiff > 0)
        return 1;
      else
        return 0;
    }
  };

  // NOTE: This may be something that's needed to correct errors we've been letting build up in the Favorites
  // priority system.
  public void fixAgentPriorities()
  {
    Agent[] allFavs = wiz.getFavorites();
    Agent[] orgSort = sortAgentsByPriority(allFavs);
    System.out.println("Original Favorite Priorities:");
    for (int i = 0; i < orgSort.length; i++)
      System.out.println(i + "-" + orgSort[i].getCause());

    System.out.println("Redoing favorite priorities");
    // Redo every priority we have set already which will now clear out any inconsistencies in the list...and likely change
    // its order in the process...
    for (int i = 0; i < allFavs.length; i++)
    {
      Agent testMe = allFavs[i];
      for (int j = 0; j < testMe.weakAgents.length; j++)
      {
        Agent weaker = wiz.getAgentForID(testMe.weakAgents[j]);
        if (weaker == null) // remove invalid weaker IDs
        {
          testMe.unbully(testMe.weakAgents[j]);
          j--;
        }
        else
          testMe.bully(weaker);
      }
    }
    Agent[] newSort = sortAgentsByPriority(allFavs);
    System.out.println("New Favorite Priorities:");
    for (int i = 0; i < newSort.length; i++)
      System.out.println(i + "-" + newSort[i].getCause());
  }

  public Agent[] sortAgentsByPriority(Object[] sortingAgents)
  {
    if (sortingAgents == null) return null;
    // Only do the Favorites, the other ones don't matter
    Agent[] sortedAgents = wiz.getFavorites();
    Arrays.sort(sortedAgents, agentCreateTimeSorter);
    //if (Sage.DBG) System.out.println("SORTING AGENTS=" + Arrays.asList(sortedAgents));
    List<Agent> finalAgentSort = new ArrayList<Agent>();
    for (int i = sortedAgents.length - 1; i >= 0; i--)
    {
      Agent insertMe = sortedAgents[i];
      if (insertMe == null) continue;
      // We put it directly after the lowest Agent that this Agent is specifically weaker
      // than.  If this Agent isn't weaker than any Agents already in the Vec, we put it at the top.
      int insertedAt = -1;
      for (int j = finalAgentSort.size() - 1; j >= 0; j--)
      {
        Agent battleMe = finalAgentSort.get(j);
        for (int k = 0; k < battleMe.weakAgents.length; k++)
        {
          if (battleMe.weakAgents[k] == insertMe.id)
          {
            insertedAt = j+1;
            //System.out.println("Inserted " + insertMe.getCause() + " behind " + battleMe.getCause());
            finalAgentSort.add(j + 1, insertMe);
            break;
          }
        }
        if (insertedAt != -1)
          break;
      }
      if (insertedAt == -1)
      {
        //System.out.println("Inserted " + insertMe.getCause() + " at front");
        finalAgentSort.add(0, insertMe);
      }
      else
      {
        // Check all of the Agents above it in the list to see if any are weaker than
        // us. If they are, then just move them below us....we need to do this again
        // for anything that we move down in the list....BUT be careful about infinite recursion
        List<Object[]> movesToCheck = new ArrayList<Object[]>();
        movesToCheck.add(new Object[] { insertMe, new Integer(insertedAt) });
        while (!movesToCheck.isEmpty())
        {
          Object[] currMove = movesToCheck.remove(0);
          insertedAt = ((Integer) currMove[1]).intValue();
          insertMe = (Agent) currMove[0];
          for (int j = insertedAt - 1; j >= 0; j--)
          {
            Agent battleMe = finalAgentSort.get(j);
            for (int k = 0; k < insertMe.weakAgents.length; k++)
            {
              if (insertMe.weakAgents[k] == battleMe.id)
              {
                finalAgentSort.add(insertedAt, finalAgentSort.remove(j));
                movesToCheck.add(new Object[] { battleMe, new Integer(insertedAt) });
                //System.out.println("MOVED " + battleMe.getCause() + " behind " + insertMe.getCause());
                insertedAt--;
                break;
              }
            }
          }
        }
      }
      //			if (Sage.DBG) System.out.println("BUILDING SORTED AGENTS=" + finalAgentSort);
    }
    if (finalAgentSort.size() == sortingAgents.length)
      return finalAgentSort.toArray(new Agent[0]);

    Set<Object> sortingSet = new HashSet<Object>(Arrays.asList(sortingAgents));
    List<Agent> rv = new ArrayList<Agent>();
    for (int i = 0; i < finalAgentSort.size(); i++)
    {
      Agent bud = finalAgentSort.get(i);
      if (sortingSet.contains(bud))
        rv.add(bud);
    }
    return rv.toArray(new Agent[0]);
  }

  public void setRecordingQuality(Agent bond, String newQual)
  {
    bond.setRecordingQuality(newQual);
    sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.FAVORITE_MODIFIED,
        new Object[] { sage.plugin.PluginEventManager.VAR_FAVORITE, bond });
  }

  public void setAutoConvertFormat(Agent bond, String newQual)
  {
    bond.setAutoConvertFormat(newQual);
    sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.FAVORITE_MODIFIED,
        new Object[] { sage.plugin.PluginEventManager.VAR_FAVORITE, bond });
  }

  public void createPriority(Agent top, Agent bottom)
  {
    //if (Sage.DBG) System.out.println("IN Carny createPriority(top=" + top + " bottom=" + bottom + ")");
    top.bully(bottom);
    //if (Sage.DBG) System.out.println("OUT Carny createPriority(top=" + top + " bottom=" + bottom + ")");
    SchedulerSelector.getInstance().kick(false);
  }

  public void setStartPadding(Agent bond, long padAmount)
  {
    bond.setStartPadding(padAmount);
    SchedulerSelector.getInstance().kick(false);
    sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.FAVORITE_MODIFIED,
        new Object[] { sage.plugin.PluginEventManager.VAR_FAVORITE, bond });
  }

  public void setStopPadding(Agent bond, long padAmount)
  {
    bond.setStopPadding(padAmount);
    SchedulerSelector.getInstance().kick(false);
    sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.FAVORITE_MODIFIED,
        new Object[] { sage.plugin.PluginEventManager.VAR_FAVORITE, bond });
  }

  public void setAgentFlags(Agent bond, int flagMask, int flagValue)
  {
    bond.setAgentFlags(flagMask, flagValue);
    SchedulerSelector.getInstance().kick(false);
    sage.plugin.PluginEventManager.postEvent(sage.plugin.PluginEventManager.FAVORITE_MODIFIED,
        new Object[] { sage.plugin.PluginEventManager.VAR_FAVORITE, bond });
  }

  public boolean isDoNotDestroy(MediaFile mf)
  {
    Agent cause = getCauseAgent(mf.getContentAiring());
    if (cause != null)
    {
      if (cause.testAgentFlag(Agent.DONT_AUTODELETE_FLAG))
        return true;
    }
    return false;
  }

  public boolean isDeleteAfterConversion(MediaFile mf)
  {
    Agent cause = getCauseAgent(mf.getContentAiring());
    if (cause != null)
    {
      if (cause.testAgentFlag(Agent.DELETE_AFTER_CONVERT_FLAG))
        return true;
    }
    return false;
  }

  public String getAutoConvertFormat(MediaFile mf)
  {
    Agent cause = getCauseAgent(mf.getContentAiring());
    if (cause != null)
    {
      return cause.getAutoConvertFormat();
    }
    return null;
  }

  public File getAutoConvertDest(MediaFile mf)
  {
    Agent cause = getCauseAgent(mf.getContentAiring());
    if (cause != null)
    {
      return cause.getAutoConvertDest();
    }
    return null;
  }

  synchronized Airing[] getPots()
  {
    return pots;
  }

  synchronized boolean isMustSee(Airing air)
  {
    return mustSeeSet.contains(air);
  }

  boolean isBaseTrend(int trendTest)
  {
    for (int i = 0; i < trends.length; i++)
      if (trends[i] == trendTest) return true;
    return false;
  }

  public boolean isLoveAir(Airing air)
  {
    return loveAirSet.contains(air);
  }

  boolean isPrepped() { return prepped; }

  void addCarnyListener(ProfilingListener x) { listeners.add(x); }
  void removeCarnyListener(ProfilingListener x) { listeners.remove(x); }

  public synchronized void updateLoves(Set<Airing> s)
  {
    loveAirSet = s;
  }
  public synchronized void updateMustSees(Set<Airing> s)
  {
    mustSeeSet = s;
  }
  public synchronized void updateCauseMap(Map<Airing, Agent> m)
  {
    causeMap = m;
  }
  public synchronized void updateWPMap(Map<Airing, Float> m)
  {
    wpMap = m;
  }
  // When airings get swapped in the DB we update them in here to still be Favorites in order
  // to be careful and ensure we don't lose any favorites due to an EPG update
  public synchronized void notifyAiringSwap(Airing oldAir, Airing newAir)
  {
    if (mustSeeSet.contains(oldAir))
    {
      // To protect against a ConcurrentModificationException in SageTVConnection
      synchronized (mustSeeSet)
      {
        mustSeeSet.remove(oldAir);
        mustSeeSet.add(newAir);
      }
      // Also check through the potentials so that gets switched too
      for (int i = 0; i < pots.length; i++)
      {
        if (pots[i] == oldAir)
          pots[i] = newAir;
      }
    }
    if (loveAirSet.contains(oldAir))
    {
      // To protect against a ConcurrentModificationException in SageTVConnection
      synchronized (loveAirSet)
      {
        loveAirSet.remove(oldAir);
        loveAirSet.add(newAir);
      }
    }
    if (wpMap.containsKey(oldAir))
    {
      // To protect against a ConcurrentModificationException in SageTVConnection
      synchronized (wpMap)
      {
        wpMap.put(newAir, wpMap.remove(oldAir));
      }
    }
    if (causeMap.containsKey(oldAir))
    {
      // To protect against a ConcurrentModificationException in SageTVConnection
      synchronized (causeMap)
      {
        causeMap.put(newAir, causeMap.remove(oldAir));
      }
    }
    swapMap.put(oldAir, newAir);
  }

  private String prefs;
  private int globalWatchCount;
  private Vector<Object[]> jobs;
  private boolean alive;
  private boolean prepped;
  private Wizard wiz;
  private int[] trends;
  private Set<Airing> loveAirSet;
  private boolean doneInit;

  private long lastCycleCompleteTime;
  private long cycleStartTime;

  // Airing -> Float
  private Map<Airing, Float> wpMap;
  // Airing -> Agent
  private Map<Airing, Agent> causeMap;
  // Airings
  private Set<Airing> mustSeeSet;
  private Airing[] pots;

  private Vector<ProfilingListener> listeners = new Vector<ProfilingListener>();

  private Map<Airing, Airing> swapMap;

  // This is the queue that all of the worker threads get their tasks from. If we ever need to stop
  // everything, all we need to do is clear this queue and all of the threads will stop working
  // fairly quickly.
  private ConcurrentLinkedQueue<Agent> agentWorkQueue;
  private ExecutorService agentWorkers;

  private static final int strComp(DBObject s1, DBObject s2)
  {
    if (s1 == s2) return 0;
    else if (s1 == null) return 1;
    else if (s2 == null) return -1;
    else return (s1.id - s2.id);
  }

  public static final Comparator<DBObject> AGENT_SORTER = new Comparator<DBObject>()
  {
    public int compare(DBObject o1, DBObject o2)
    {
      if (o1 == o2)
        return 0;
      if (o1 == null)
        return 1;
      if (o2 == null)
        return -1;
      Agent a1 = (Agent) o1;
      Agent a2 = (Agent) o2;
      int i = a1.agentMask - a2.agentMask;
      if (i != 0)
        return i;
      if (a1.title != a2.title)
        return strComp(a1.title, a2.title);
      if (a1.category != a2.category)
        return strComp(a1.category, a2.category);
      if (a1.subCategory != a2.subCategory)
        return strComp(a1.subCategory, a2.subCategory);
      if (a1.person != a2.person)
        return strComp(a1.person, a2.person);
      if (a1.role != a2.role)
        return a1.role - a2.role;
      if (a1.rated != a2.rated)
        return strComp(a1.rated, a2.rated);
      if (a1.pr != a2.pr)
        return strComp(a1.pr, a2.pr);
      if (a1.network != a2.network)
        return strComp(a1.network, a2.network);
      if (a1.year != a2.year)
        return strComp(a1.year, a2.year);
      if (a1.slotType != a2.slotType)
        return a1.slotType - a2.slotType;
      if (a1.timeslots != a2.timeslots)
      {
        if (a1.timeslots == null)
          return -1;
        else if (a2.timeslots == null)
          return 1;
        else if (a1.timeslots.length != a2.timeslots.length)
          return a1.timeslots.length - a2.timeslots.length;
        for (int j = 0; j < a1.timeslots.length; j++)
          if (a1.timeslots[j] != a2.timeslots[j])
            return a1.timeslots[j] - a2.timeslots[j];
      }
      i = a1.chanName.compareTo(a2.chanName);
      if (i != 0)
        return i;
      return a1.keyword.compareTo(a2.keyword);
    }
  };

/*	public void launchAgentBrowser()
	{
		final java.awt.Frame f = new java.awt.Frame("AgentBrowser");
		f.setLayout(new java.awt.GridBagLayout());
		java.awt.GridBagConstraints gbc = new java.awt.GridBagConstraints();
		final java.awt.Choice c = new java.awt.Choice();
		DBObject[] allAgents = wiz.getRawAccess(Wizard.AGENT_CODE, Wizard.AGENTS_BY_CARNY_CODE);
		java.util.Set trendSet = new java.util.HashSet();
		for (int j = 0; j < allAgents.length; j++)
		{
			Agent bond = (Agent) allAgents[j];
			if (bond != null && trendSet.add(bond.getNameForType()))
				c.add(bond.getNameForType());
		}
		c.add("WATCHED");
		c.add("WASTED");
		final java.awt.List lis = new java.awt.List();
		final java.awt.TextArea ta = new java.awt.TextArea();
		final java.util.Vector agentVec = new java.util.Vector();
		gbc.gridx = gbc.gridy = 0;
		gbc.weightx = 1;
		gbc.weighty = 0;
		gbc.fill = java.awt.GridBagConstraints.HORIZONTAL;
		f.add(c, gbc);
		gbc.gridy++;
		gbc.weighty = 1;
		gbc.fill = java.awt.GridBagConstraints.BOTH;
		f.add(lis, gbc);
		gbc.gridy++;
		f.add(ta, gbc);
		c.addItemListener(new java.awt.event.ItemListener()
		{
			public void itemStateChanged(java.awt.event.ItemEvent evt)
			{
				String str = c.getSelectedItem();
				if (str == null) return;
				lis.removeAll();
				agentVec.clear();
				if ("WATCHED".equals(str))
				{
					DBObject[] all = wiz.getRawAccess(Wizard.WATCH_CODE, (byte) 0);
					for (int j = 0; j < all.length; j++)
					{
						if (all[j] != null)
							lis.add(all[j].toString());
					}
				}
				else if ("WASTED".equals(str))
				{
					DBObject[] all = wiz.getRawAccess(Wizard.WASTED_CODE, (byte) 0);
					for (int j = 0; j < all.length; j++)
					{
						if (all[j] != null)
							lis.add(all[j].toString());
					}
				}
				else
				{
					DBObject[] allAgents = wiz.getRawAccess(Wizard.AGENT_CODE, Wizard.AGENTS_BY_CARNY_CODE);
					for (int j = 0; j < allAgents.length; j++)
					{
						if (allAgents[j] != null && ((Agent)allAgents[j]).getNameForType().equals(str))
						{
							agentVec.addElement(allAgents[j]);
							lis.add(allAgents[j].toString());
						}
					}
				}
			}
		});
		lis.addItemListener(new java.awt.event.ItemListener()
		{
			public void itemStateChanged(java.awt.event.ItemEvent evt)
			{
				int sel = lis.getSelectedIndex();
				if (sel < 0 || sel >= agentVec.size()) return;
				Agent bond = (Agent) agentVec.elementAt(sel);
				StringBuffer sb = new StringBuffer();
				sb.append(bond.toString());
				sb.append("\r\n");
				sb.append("Negator=");
				sb.append(bond.isNegativeNelly() + "\r\n");
				sb.append("WeakAgents=\r\n");
				for (int i = 0; i < bond.weakAgents.length; i++)
				{
					Agent tempAgent = wiz.getAgentForID(bond.weakAgents[i]);
					if (tempAgent != null)
					{
						sb.append(tempAgent.toString());
						sb.append("\r\n");
					}
				}
				Airing[] relAirs = bond.getRelatedAirings(null);
				sb.append(relAirs.length);
				sb.append(" Related Airings\r\n");
				for (int i = 0; i < relAirs.length; i++)
				{
					sb.append(relAirs[i]);
					sb.append("\r\n");
				}
				ta.setText(sb.toString());
			}
		});
		f.addWindowListener(new java.awt.event.WindowAdapter()
		{
			public void windowClosing(java.awt.event.WindowEvent evt)
			{
				f.dispose();
			}
		});
		f.setSize(500, 500);
		f.setVisible(true);
	}*/
  public static interface ProfilingListener
  {
    public boolean updateLoves(Set<Airing> s);
    public boolean updateMustSees(Set<Airing> s);
    public boolean updateCauseMap(Map<Airing, Agent> m);
    public boolean updateWPMap(Map<Airing, Float> m);
  }

  public class CarnyAgentWorker implements Callable<Boolean>
  {
    final String paidProgRez = Sage.rez("Paid_Programming");

    // These do not change between any of the threads, so it is ok for all of the threads to share
    // these relatively constant variables.
    private final boolean controlCPUUsage;
    private final int totalThreads;
    private final boolean doneInit;
    private final Airing allAirs[];
    private final Airing remAirs[];
    private final Airing watchAirs[];
    private final Airing wastedAirs[];
    private final Map<Integer, Airing[]> allAirsMap;
    private final Map<Integer, Airing[]> remAirsMap;
    private final Map<Integer, Airing[]> watchAirsMap;
    private final Map<Integer, Airing[]> wastedAirsMap;

    // These must be synchronized because all of the threads will be adding.
    private final List<Agent> traitors;
    private final Set<Airing> newLoveAirSet;
    private final Set<Airing> blackBalled;
    private final Set<Airing> airSet;
    private final Map<Airing, Float> newWPMap;
    private final Map<Airing, Agent> newCauseMap;
    private final Set<Airing> watchedPotsToClear;
    private final Set<Airing> newMustSeeSet;

    public CarnyAgentWorker(boolean controlCPUUsage, int totalThreads, boolean doneInit,
                            Airing[] allAirs, Airing[] remAirs, Airing[] watchAirs, Airing[] wastedAirs,
                            Map<Integer, Airing[]> allAirsMap,
                            Map<Integer, Airing[]> remAirsMap,
                            Map<Integer, Airing[]> watchAirsMap,
                            Map<Integer, Airing[]> wastedAirsMap, List<Agent> traitors,
                            Set<Airing> newLoveAirSet, Set<Airing> blackBalled, Set<Airing> airSet,
                            Map<Airing, Float> newWPMap, Map<Airing, Agent> newCauseMap,
                            Set<Airing> watchedPotsToClear, Set<Airing> newMustSeeSet)
    {
      this.controlCPUUsage = controlCPUUsage;
      this.totalThreads = totalThreads;
      this.doneInit = doneInit;
      this.allAirs = allAirs;
      this.remAirs = remAirs;
      this.watchAirs = watchAirs;
      this.wastedAirs = wastedAirs;
      this.allAirsMap = allAirsMap;
      this.remAirsMap = remAirsMap;
      this.watchAirsMap = watchAirsMap;
      this.wastedAirsMap = wastedAirsMap;
      this.traitors = traitors;
      this.newLoveAirSet = newLoveAirSet;
      this.blackBalled = blackBalled;
      this.airSet = airSet;
      this.newWPMap = newWPMap;
      this.newCauseMap = newCauseMap;
      this.watchedPotsToClear = watchedPotsToClear;
      this.newMustSeeSet = newMustSeeSet;
    }

    // Returns false if the whole queue needs to be stopped for a new job. The above shared
    // variables must be local to the method controlling the execution of these callables, so we
    // will see the stopping process early message at least once for each processing thread, but
    // there will be no concerns about old jobs breaking new jobs.
    @Override
    public Boolean call() throws Exception
    {
      final StringBuffer sbCache = new StringBuffer();
      final List<Airing> potsWorkCache = new ArrayList<>();
      final List<Airing> remWorkCache = new ArrayList<>();
      if (Sage.DBG) System.out.println("Carny agent worker is starting a new job...");

      Agent currAgent;
      while (true)
      {
        currAgent = agentWorkQueue.poll();
        // The queue is empty.
        if (currAgent == null)
        {
          if (Sage.DBG) System.out.println("Carny agent worker is out of work.");
          return true;
        }

        if (controlCPUUsage)
          try{Thread.sleep(SLEEP_PERIOD * totalThreads);}catch(Exception e){}
        if (doneInit)
        {
          // Check to see if something else is a higher priority to calculate.
          // NOTE: If we've been trying to build the profile for a half hour and haven't finished then it's
          // likely that stopping early could cause us to never finish since recordings end/start so often.
          // So in that case we do NOT stop processing early and just continue on our merry way!
          if (!shouldContinueStdProcessing(Sage.eventTime() - cycleStartTime < 30*Sage.MILLIS_PER_MIN))
          {
            if (Sage.DBG) System.out.println("Carny agent worker is quitting early to start a new job...");
            return false; // just bail, it'll pick it all up afterwards
          }
        }

        if (!currAgent.calcWatchProb(controlCPUUsage, potsWorkCache, sbCache, watchAirs, watchAirsMap, wastedAirs, wastedAirsMap))
        {
          traitors.add(currAgent);
          continue;
        }

        currAgent.getRelatedAirings(allAirs, true, controlCPUUsage, sbCache, allAirsMap, potsWorkCache);

        if (currAgent.isFavorite())
        {
          newLoveAirSet.addAll(potsWorkCache);

          // Also check the rem airs so we're sure we get EVERYTHING in the DB
          // that applies to this Favorite included in the group.
          currAgent.getRelatedAirings(remAirs, false, controlCPUUsage, sbCache, remAirsMap, remWorkCache);
          newLoveAirSet.addAll(remWorkCache);
          remWorkCache.clear();
        }

        // Check to see if this Agent is a Favorite who has a keep at most limit set with
        // manual deleting. In that case, we don't add any of its future airings to the
        // mustSeeSet or to the pots, but we do put them in the loveAirSet.
        boolean dontScheduleThisAgent = false;
        if (currAgent.isFavorite() && currAgent.testAgentFlag(Agent.DONT_AUTODELETE_FLAG) &&
          currAgent.getAgentFlag(Agent.KEEP_AT_MOST_MASK) > 0)
        {
          int fileCount = 0;
          for (int j = 0, potsWorkCacheSize = potsWorkCache.size(); j < potsWorkCacheSize; j++)
          {
            MediaFile mf = wiz.getFileForAiring(potsWorkCache.get(j));
            if (mf != null && mf.isCompleteRecording())
              fileCount++;
          }
          int keepAtMost = currAgent.getAgentFlag(Agent.KEEP_AT_MOST_MASK);
          if (fileCount >= keepAtMost)
          {
            dontScheduleThisAgent = true;
          }
        }

        boolean negator = currAgent.isNegativeNelly();
        for (int j = 0, potsWorkCacheSize = potsWorkCache.size(); j < potsWorkCacheSize; j++)
        {
          Airing agentPot = potsWorkCache.get(j);
          if (negator)
          {
            blackBalled.add(agentPot);
            continue;
          }

          // Skip this stuff
          if (paidProgRez.equalsIgnoreCase(agentPot.getTitle()) || wiz.isNoShow(agentPot.showID))
            continue;

          airSet.add(agentPot);

              /*
               * NOTE: On 7/22/03 I moved the isWatched() down from the beginning of this loop.
               * We want to keep watched airings in the Agent lookup table so we can
               * use the agentFlags on them even after they're watched. So now watched
               * airings will have a cause agent, but their WP will be zero.
               */
              /*
               * NOTE: Narflex - 8/4/08 - Fixed bug where we wouldn't use the higher priority favorite
               * in the cause map since comparing watchProb would be equal; this would then cause scheduling
               * issues because the wrong agent was used for the scheduling that should have been higher priority sometimes
               */

          synchronized (newWPMap)
          {
            Float wp = newWPMap.get(agentPot);
            boolean replaceInMap = false;
            if (wp == null || (currAgent.watchProb > wp))
              replaceInMap = true;
            if (wp != null && currAgent.watchProb == wp && currAgent.isFavorite())
            {
              // Check for agent priority
              Agent oldAgent = newCauseMap.get(agentPot);
              if (oldAgent == null || doBattle(oldAgent, currAgent) == currAgent)
                replaceInMap = true;
            }
            if (replaceInMap)
            {
              newCauseMap.put(agentPot, currAgent);
                  /*
                   * We need to put the WP in temporarily even for watched stuff so the most powerful
                   * Agent shows up in the map, otherwise a cat agent could override a favorite agent on a watched show
                   */
              newWPMap.put(agentPot, currAgent.watchProb);
              if (agentPot.isWatchedForSchedulingPurpose())
              {
                watchedPotsToClear.add(agentPot);
              }
            }
          }

          if (agentPot.isWatchedForSchedulingPurpose())
            continue;
          if (dontScheduleThisAgent)
          {
            MediaFile mf = wiz.getFileForAiring(agentPot);
            if (mf == null || !mf.isCompleteRecording())
            {
              watchedPotsToClear.add(agentPot);
              continue;
            }
          }
          if (currAgent.isFavorite())
            newMustSeeSet.add(agentPot);
        }
      }
    }
  }
}
