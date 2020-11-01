package org.batfish.symbolic.smt;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.BitVecExpr;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import com.microsoft.z3.Optimize;
import com.microsoft.z3.Sort;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Symbol;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException; 
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.Nullable;
import org.batfish.datamodel.BgpNeighbor;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.HeaderSpace;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.IpProtocol;
import org.batfish.datamodel.IpWildcard;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixRange;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.StaticRoute;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.TcpFlags;
import org.batfish.datamodel.acl.MatchHeaderSpace;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.IntExpr;
import org.batfish.datamodel.routing_policy.expr.LiteralInt;
import org.batfish.datamodel.routing_policy.expr.MatchProtocol;
import org.batfish.datamodel.routing_policy.statement.If;
import org.batfish.datamodel.routing_policy.statement.SetLocalPreference;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.datamodel.routing_policy.statement.Statements;
import org.batfish.datamodel.visitors.IpSpaceMayIntersectWildcard;
import org.batfish.symbolic.AstVisitor;
import org.batfish.symbolic.CommunityVar;
import org.batfish.symbolic.Graph;
import org.batfish.symbolic.GraphEdge;
import org.batfish.symbolic.OspfType;
import org.batfish.symbolic.Protocol;
import org.batfish.symbolic.collections.Table2;

/**
 * A class responsible for building a symbolic encoding of the network for a particular packet. The
 * encoding is heavily specialized based on the PolicyQuotient class, and what optimizations it
 * indicates are possible.
 *
 * @author Ryan Beckett
 */
class EncoderSlice {

  private static final int BITS = 0;

  private Encoder _encoder;

  private String _sliceName;

  private HeaderSpace _headerSpace;

  private Optimizations _optimizations;

  private LogicalGraph _logicalGraph;

  private SymbolicDecisions _symbolicDecisions;

  private SymbolicPacket _symbolicPacket;

  private Map<GraphEdge, BoolExpr> _inboundAcls;

  private Map<GraphEdge, BoolExpr> _outboundAcls;

  private Table2<String, GraphEdge, BoolExpr> _forwardsAcross;

  private List<SymbolicRoute> _allSymbolicRoutes;

  private Map<String, SymbolicRoute> _ospfRedistributed;

  private Map<String, SymbolicRoute> _softOspfRedistributed;

  private Table2<String, Protocol, Set<Prefix>> _originatedNetworks;

  private Table2<String, Protocol, Set<Prefix>> _allOriginatedNetworks;

  private Set<GraphEdge> _bothIfaceEdges;

  private Table2<String, Protocol, Set<Protocol>> _softRedistributed;

  private boolean _isTCE;

  private boolean _first;

  private EncoderSlice _existsES;

  private Map<GraphEdge, BoolExpr> _staticRouteAddSoft;

  private EncoderSlice _oldES;

  private TreeSet<Integer> _localPref;

  private Map<String, ArithExpr> _localPrefMap;

  private Set<LogicalEdge> _ospfAllow;

  private Set<LogicalEdge> _bgpAllow;

  private Map<String, BoolExpr> _disableOSPF;

  private Map<String, BoolExpr> _disableRedis;

  private Map<String, BoolExpr> _enableRoute;

  private Set<BoolExpr> _allBoolVars;

  private Set<ArithExpr> _allArithVars;

  private Set<BitVecExpr> _allBVVars;

  private Map<BitVecExpr, Integer> _allBVValues;

  private List<Symbol> _allBoolVarsList;

  private List<Symbol> _allArithVarsList;

  private List<Symbol> _allBVVarsList;

  private Map<Symbol, Integer> _allBVValuesMap;

  public Map<String, BoolExpr> _routerConsMap;

  public Map<String, Map<String, Map<String, Map<String, Map<String, BoolExpr>>>>> _Tree;


  public int aclWeight;
  public int bgpWeight;
  public int ospfWeight;
  public int enableAdjWeight;
  public int staticWeight;
  public int importWeight;
  public int redisWeight;
  public int localprefWeight;
  private int exportRuleNum;

  /**
   * Create a new encoding slice
   *
   * @param enc The parent encoder object
   * @param h The packet headerspace of interest
   * @param graph The network graph
   * @param sliceName The name of this slice
   */
  EncoderSlice(Encoder enc, HeaderSpace h, Graph graph, String sliceName) {
    _encoder = enc;
    _Tree = _encoder._abstractTree;
    _routerConsMap = _encoder._routerConsMap;
    _first = (enc.getId() == 0);
    _localPrefMap = new HashMap<>();
    _disableOSPF = new HashMap<>();
    _disableRedis = new HashMap<>();
    _enableRoute = new HashMap<>();
    _localPref = new TreeSet<Integer>();

    _allBoolVars = new HashSet<>();
    _allArithVars = new HashSet<>();
    _allBVVars = new HashSet<>();
    _allBVValues = new HashMap<>();

    _allBoolVarsList = new ArrayList<>();
    _allArithVarsList = new ArrayList<>();
    _allBVVarsList = new ArrayList<>();
    _allBVValuesMap = new HashMap<>();

    if (!_first) {
      _oldES = enc.getPreviousEncoderSlice();
      _localPrefMap = _oldES.getLocalPrefMap();
      _disableOSPF = _oldES.getOspfDisableMap();
      _disableRedis = _oldES.getRedisDisableMap();
      _localPref = _oldES.getLocalPrefSet();
      _enableRoute = _oldES.getEnableRouteMap();
    }

    _sliceName = sliceName;
    _headerSpace = h;
    _allSymbolicRoutes = new ArrayList<>();
    _optimizations = new Optimizations(this);
    _logicalGraph = new LogicalGraph(graph);
    _symbolicDecisions = new SymbolicDecisions();
    _symbolicPacket = new SymbolicPacket(enc.getCtx(), enc.getId(), _sliceName);
    _bothIfaceEdges = new HashSet<>();
    _softRedistributed = new Table2<>();
    _isTCE = false;
    _staticRouteAddSoft = new HashMap<>();

    _ospfAllow = new HashSet<>();

    _bgpAllow = new HashSet<>();


    setWeight();

    enc.getAllVariables().put(_symbolicPacket.getDstIp().toString() + _encoder.getStringId(),
     _symbolicPacket.getDstIp());
    enc.getAllVariables().put(_symbolicPacket.getSrcIp().toString() + _encoder.getStringId(),
     _symbolicPacket.getSrcIp());
    enc.getAllVariables()
        .put(_symbolicPacket.getDstPort().toString() + _encoder.getStringId(),
         _symbolicPacket.getDstPort());
    enc.getAllVariables()
        .put(_symbolicPacket.getSrcPort().toString() + _encoder.getStringId(),
         _symbolicPacket.getSrcPort());
    enc.getAllVariables()
        .put(_symbolicPacket.getIcmpCode().toString() + _encoder.getStringId(),
         _symbolicPacket.getIcmpCode());
    enc.getAllVariables()
        .put(_symbolicPacket.getIcmpType().toString() + _encoder.getStringId(),
         _symbolicPacket.getIcmpType());
    enc.getAllVariables().put(_symbolicPacket.getTcpAck().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpAck());
    enc.getAllVariables().put(_symbolicPacket.getTcpCwr().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpCwr());
    enc.getAllVariables().put(_symbolicPacket.getTcpEce().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpEce());
    enc.getAllVariables().put(_symbolicPacket.getTcpFin().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpFin());
    enc.getAllVariables().put(_symbolicPacket.getTcpPsh().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpPsh());
    enc.getAllVariables().put(_symbolicPacket.getTcpRst().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpRst());
    enc.getAllVariables().put(_symbolicPacket.getTcpSyn().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpSyn());
    enc.getAllVariables().put(_symbolicPacket.getTcpUrg().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpUrg());
    enc.getAllVariables()
        .put(_symbolicPacket.getIpProtocol().toString(), _symbolicPacket.getIpProtocol());

    _inboundAcls = new HashMap<>();
    _outboundAcls = new HashMap<>();
    _forwardsAcross = new Table2<>();
    _ospfRedistributed = new HashMap<>();
    _softOspfRedistributed = new HashMap<>();
    _originatedNetworks = new Table2<>();
    _allOriginatedNetworks = new Table2<>(); 

    initOptimizations();
    initOriginatedPrefixes();

    /*
    if (_first) {
      initOptimizations();
      initOriginatedPrefixes();
      initCommunities();
    } else {
      _optimizations = _oldES.getOptimizations();
      _originatedNetworks = _oldES.getOriginatedNetworks();
      _allOriginatedNetworks = _oldES.getAllOriginatedNetworks(); 
      _allCommunities = _oldES.getAllCommunities();
      _namedCommunities = _oldES.getNamedCommunities();
      _communityDependencies = _oldES.getCommunityDependencies();

    }*/
    initRedistributionProtocols();
    initVariables();
    initAclFunctions();
    initForwardingAcross();
  }


  /**
   * Create a new encoding slice
   *
   * @param enc The parent encoder object
   * @param h The packet headerspace of interest
   * @param graph The network graph
   * @param sliceName The name of this slice
   * @param isTCE checks if this is an encoding for Traffic Class only (i.e. Encoding exists for
   * destination)
   */
  EncoderSlice(Encoder enc, HeaderSpace h, Graph graph, String sliceName, EncoderSlice existsES) {
    _encoder = enc;
    _Tree = _encoder._abstractTree;
    _routerConsMap = _encoder._routerConsMap;
    _first = (enc.getId() == 0);
    _localPrefMap = new HashMap<>();
    _disableOSPF = new HashMap<>();
    _disableRedis = new HashMap<>();
    _enableRoute = new HashMap<>();
    _localPref = new TreeSet<Integer>();

    _allBoolVars = existsES.getAllBoolVars();
    _allArithVars = existsES.getAllArithVars();
    _allBVVars = existsES.getAllBVVars();
    _allBVValues = existsES.getAllBVValues();

    _allBoolVarsList = existsES.getAllBoolVarsList();
    _allArithVarsList = existsES.getAllArithVarsList();
    _allBVVarsList = existsES.getAllBVVarsList();
    _allBVValuesMap = existsES.getAllBVValuesMap();


    if (!_first) {
      _oldES = enc.getPreviousEncoderSlice();
      _localPrefMap = _oldES.getLocalPrefMap();
      _disableOSPF = _oldES.getOspfDisableMap();
      _disableRedis = _oldES.getRedisDisableMap();
      _localPref = _oldES.getLocalPrefSet();
      _enableRoute = _oldES.getEnableRouteMap();
    }
    _sliceName = sliceName;
    _headerSpace = h;
    _allSymbolicRoutes = existsES.getAllSymbolicRecords();
    _optimizations = existsES.getOptimizations();
    _logicalGraph = existsES.getLogicalGraph();
    _symbolicDecisions = existsES.getSymbolicDecisions();
    _symbolicPacket = new SymbolicPacket(enc.getCtx(), enc.getId(), _sliceName);
    _bothIfaceEdges = existsES.getBothIfaceEdges();
    _softRedistributed = existsES.getSoftRedistributed();
    _isTCE = true;
    _existsES = existsES;
    _staticRouteAddSoft = new HashMap<>();

    _ospfAllow = new HashSet<>();

    _bgpAllow = new HashSet<>();

    setWeight();
 
    enc.getAllVariables().put(_symbolicPacket.getDstIp().toString() + _encoder.getStringId(),
     _symbolicPacket.getDstIp());
    enc.getAllVariables().put(_symbolicPacket.getSrcIp().toString() + _encoder.getStringId(),
     _symbolicPacket.getSrcIp());
    enc.getAllVariables()
        .put(_symbolicPacket.getDstPort().toString() + _encoder.getStringId(),
         _symbolicPacket.getDstPort());
    enc.getAllVariables()
        .put(_symbolicPacket.getSrcPort().toString() + _encoder.getStringId(),
         _symbolicPacket.getSrcPort());
    enc.getAllVariables()
        .put(_symbolicPacket.getIcmpCode().toString() + _encoder.getStringId(),
         _symbolicPacket.getIcmpCode());
    enc.getAllVariables()
        .put(_symbolicPacket.getIcmpType().toString() + _encoder.getStringId(),
         _symbolicPacket.getIcmpType());
    enc.getAllVariables().put(_symbolicPacket.getTcpAck().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpAck());
    enc.getAllVariables().put(_symbolicPacket.getTcpCwr().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpCwr());
    enc.getAllVariables().put(_symbolicPacket.getTcpEce().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpEce());
    enc.getAllVariables().put(_symbolicPacket.getTcpFin().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpFin());
    enc.getAllVariables().put(_symbolicPacket.getTcpPsh().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpPsh());
    enc.getAllVariables().put(_symbolicPacket.getTcpRst().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpRst());
    enc.getAllVariables().put(_symbolicPacket.getTcpSyn().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpSyn());
    enc.getAllVariables().put(_symbolicPacket.getTcpUrg().toString() + _encoder.getStringId(),
     _symbolicPacket.getTcpUrg());
    enc.getAllVariables()
        .put(_symbolicPacket.getIpProtocol().toString(), _symbolicPacket.getIpProtocol());

    _inboundAcls = new HashMap<>();
    _outboundAcls = new HashMap<>();
    _forwardsAcross = new Table2<>();

    _ospfRedistributed = existsES.getOspfRedistributed();
    _softOspfRedistributed = existsES.getSoftOspfRedistributed();
    _originatedNetworks = existsES.getOriginatedNetworks();
    _allOriginatedNetworks = existsES.getAllOriginatedNetworks(); 

    //initRedistributionProtocols();
    addDataForwardingVariables();
    initAclFunctions();
    initForwardingAcross();
  }

  void setWeight() {
    aclWeight = 1;
    bgpWeight = 1;
    ospfWeight = 1;
    enableAdjWeight = 1;
    staticWeight = 1;
    importWeight = 1;
    redisWeight = 1;
    localprefWeight = 1;

    if (_encoder._weightMap.containsKey("acl")) {
      aclWeight = _encoder._weightMap.get("acl");  
    }
    if (_encoder._weightMap.containsKey("bgp")) {
      bgpWeight = _encoder._weightMap.get("bgp");  
    }
    if (_encoder._weightMap.containsKey("ospf")) {
      ospfWeight = _encoder._weightMap.get("ospf");  
    }
    if (_encoder._weightMap.containsKey("enable")) {
      enableAdjWeight = _encoder._weightMap.get("enable");  
    }
    if (_encoder._weightMap.containsKey("static")) {
      staticWeight = _encoder._weightMap.get("static");  
    }
    if (_encoder._weightMap.containsKey("filter")) {
      importWeight = _encoder._weightMap.get("filter");  
    }
    if (_encoder._weightMap.containsKey("redis")) {
      redisWeight = _encoder._weightMap.get("redis");  
    }
    if (_encoder._weightMap.containsKey("localpref")) {
      localprefWeight = _encoder._weightMap.get("localpref");  
    }


  }

  // Add a variable to the encoding
  void add(BoolExpr e) {
    _encoder.add(e);
  }

  // @archie Add soft constraint to the encoding
  void addSoft(BoolExpr e, int weight, String name) {
    _encoder.addSoft(e, weight, name);
  }

  // Symbolic mkFalse value
  BoolExpr mkFalse() {
    return _encoder.mkFalse();
  }

  // Symbolic true value
  BoolExpr mkTrue() {
    return _encoder.mkTrue();
  }

  // Create a symbolic boolean
  BoolExpr mkBool(boolean val) {
    return _encoder.mkBool(val);
  }

  // Symbolic boolean negation
  BoolExpr mkNot(BoolExpr e) {
    return _encoder.mkNot(e);
  }

  // Symbolic boolean disjunction
  BoolExpr mkOr(BoolExpr... vals) {
    return _encoder.mkOr(vals);
  }

  // Symbolic boolean implication
  BoolExpr mkImplies(BoolExpr e1, BoolExpr e2) {
    return getCtx().mkImplies(e1, e2);
  }

  // Symbolic greater than
  BoolExpr mkGt(Expr e1, Expr e2) {
    return _encoder.mkGt(e1, e2);
  }

  // Symbolic arithmetic subtraction
  ArithExpr mkSub(ArithExpr e1, ArithExpr e2) {
    return _encoder.mkSub(e1, e2);
  }

  // Symbolic if-then-else for booleans
  BoolExpr mkIf(BoolExpr cond, BoolExpr case1, BoolExpr case2) {
    return _encoder.mkIf(cond, case1, case2);
  }

  // Symbolic if-then-else for bit vectors
  BitVecExpr mkIf(BoolExpr cond, BitVecExpr case1, BitVecExpr case2) {
    return (BitVecExpr) _encoder.getCtx().mkITE(cond, case1, case2);
  }

  // Symbolic if-then-else for arithmetic
  ArithExpr mkIf(BoolExpr cond, ArithExpr case1, ArithExpr case2) {
    return _encoder.mkIf(cond, case1, case2);
  }

  // Symbolic equality
  BoolExpr mkEq(Expr e1, Expr e2) {
    return getCtx().mkEq(e1, e2);
  }

  // Create a symbolic integer
  ArithExpr mkInt(long l) {
    return _encoder.mkInt(l);
  }

  // Symbolic boolean conjunction
  BoolExpr mkAnd(BoolExpr... vals) {
    return _encoder.mkAnd(vals);
  }

  // Symbolic greater than or equal
  BoolExpr mkGe(Expr e1, Expr e2) {
    return _encoder.mkGe(e1, e2);
  }

  // Symbolic less than or equal
  BoolExpr mkLe(Expr e1, Expr e2) {
    return _encoder.mkLe(e1, e2);
  }

  // Symbolic less than
  BoolExpr mkLt(Expr e1, Expr e2) {
    return _encoder.mkLt(e1, e2);
  }

  // Symbolic arithmetic addition
  ArithExpr mkSum(ArithExpr e1, ArithExpr e2) {
    return _encoder.mkSum(e1, e2);
  }

  // Check equality of expressions with null checking
  BoolExpr safeEq(Expr x, Expr value) {
    if (x == null) {
      return mkTrue();
    }
    return mkEq(x, value);
  }

  // Check for equality of symbolic enums after accounting for null
  <T> BoolExpr safeEqEnum(SymbolicEnum<T> x, T value) {
    if (x == null) {
      return mkTrue();
    }
    return x.checkIfValue(value);
  }

  // Check for equality of symbolic enums after accounting for null
  <T> BoolExpr safeEqEnum(SymbolicEnum<T> x, SymbolicEnum<T> y) {
    if (x == null) {
      return mkTrue();
    }
    return x.mkEq(y);
  }

  /*
   * Initialize boolean expressions to represent ACLs on each interface.
   */
  private void initAclFunctions() {
    for (Entry<String, List<GraphEdge>> entry : getGraph().getEdgeMap().entrySet()) {
      String router = entry.getKey();
      //System.out.println("Router" + router);
      List<GraphEdge> edges = entry.getValue();


      boolean noChange = router.endsWith("a") || router.endsWith("b");

      int ruleNum = 0;
      for (GraphEdge ge : edges) {

        if (noChange == true) {
          BoolExpr thisAcl = getCtx().mkBoolConst(router);
          _outboundAcls.put(ge, thisAcl);
          _inboundAcls.put(ge, thisAcl);
          continue;
        }

        Interface i = ge.getStart();
        //System.out.println(ge.toString() + " *");
        IpAccessList outbound = i.getOutgoingFilter();
        if (outbound != null) {// && existsACL(outbound)) {
          //System.out.println("Outbound ACL " + outbound.toString());
          String outName =
              String.format(
                  "%d_%s_%s_%s_%s_%s",
                  _encoder.getId(),
                  _sliceName,
                  router,
                  i.getName(),
                  "OUTBOUND",
                  outbound.getName());

          BoolExpr outAcl = getCtx().mkBoolConst(outName);
          BoolExpr outAclFunc = computeACL(outbound);
          //* ARCHIE REMOVE
          BoolExpr outAclRemove = getCtx().mkBoolConst(_encoder.getId() + "_" + outName + "Remove");
          if (_encoder._repairObjective == 0) {
            addSoft(mkNot(outAclRemove), aclWeight, "SoftOutAclRemove");
          }
          _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), mkNot(outAclRemove)));
          //*/
          // @archie outAclRemove is soft constraint to do out ACL remove
          add(mkEq(outAcl, outAclFunc));
          //ARCHIR ADD THIS BACK 
          //_outboundAcls.put(ge, outAcl);
          ///* ARCHIE REMOVE 
          _outboundAcls.put(ge, mkOr(outAcl,outAclRemove));
          Map<String, Map<String, BoolExpr>> ruleRemove = new HashMap<>();
          Map<String, BoolExpr> remove = new HashMap<>();
          remove.put("remove", outAclRemove);
          ruleRemove.put("match", remove);
          _Tree.get(router).get("pfilter").put(Integer.toString(ruleNum), ruleRemove);
          ruleNum += 1;
        } else {
          String outName =
              String.format(
                  "%d_%s_%s_%s_%s_%s",
                  _encoder.getId(),
                  _sliceName,
                  router,
                  i.getName(),
                  "OUTBOUND",
                  "SOFT");
          //* ARCHIE REMOVE
          BoolExpr outAcl = getCtx().mkBoolConst(_encoder.getId() + "_" + outName + "Add");
          // @archie outAcl is soft constraint to do out ACL add
          if (_encoder._repairObjective == 0) {
            addSoft(outAcl, aclWeight, "SoftOutAclAdd");
          }
          _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), outAcl));
          _outboundAcls.put(ge, outAcl);
          Map<String, Map<String, BoolExpr>> ruleAdd = new HashMap<>();
          Map<String, BoolExpr> add = new HashMap<>();
          add.put("add", outAcl);
          ruleAdd.put("match", add);
          _Tree.get(router).get("pfilter").put(Integer.toString(ruleNum), ruleAdd);
          ruleNum += 1;
          //*/
        }

        IpAccessList inbound = i.getIncomingFilter();
        if (inbound != null) {// && existsACL(inbound)) {
          //System.out.println("Inbound ACL " + inbound.toString());
          String inName =
              String.format(
                  "%d_%s_%s_%s_%s_%s",
                  _encoder.getId(), _sliceName, router, i.getName(), "INBOUND", inbound.getName());

          BoolExpr inAcl = getCtx().mkBoolConst(inName);
          BoolExpr inAclFunc = computeACL(inbound);
          /*if (existsACL(inbound)) {
            System.out.println(router + i.getName() + "-in");
          }*/
          //* ARCHIE REMOVE
          BoolExpr inAclRemove = getCtx().mkBoolConst(_encoder.getId() + "_" + inName + "Remove");
          if (_encoder._repairObjective == 0) {
            addSoft(mkNot(inAclRemove), aclWeight, "SoftInAclRemove");
          }
          _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), mkNot(inAclRemove)));
          // @archie inAclRemove is soft constraint to do in ACL remove
          //*/
          // ARCHIE ADD THIS BACK 
          add(mkEq(inAcl, inAclFunc));
          //_inboundAcls.put(ge, inAcl);
          ///* ARCHIE REMOVE 
          _inboundAcls.put(ge, mkOr(inAcl,inAclRemove));
          Map<String, Map<String, BoolExpr>> ruleRemove = new HashMap<>();
          Map<String, BoolExpr> remove = new HashMap<>();
          remove.put("remove", inAclRemove);
          ruleRemove.put("match", remove);
          _Tree.get(router).get("pfilter").put(Integer.toString(ruleNum), ruleRemove);
          ruleNum += 1;

        } else {
          String inName =
              String.format(
                  "%d_%s_%s_%s_%s_%s",
                  _encoder.getId(), _sliceName, router, i.getName(), "INBOUND", "SOFT");
          //* ARCHIE REMOVE
          BoolExpr inAcl = getCtx().mkBoolConst(_encoder.getId() + "_" + inName + "Add");
          // @archie inAcl is soft constraint to do out ACL add
          if (_encoder._repairObjective == 0) {
            addSoft(inAcl, aclWeight, "SoftInAclAdd");
          }
          _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), inAcl));
          _inboundAcls.put(ge, inAcl);
          Map<String, Map<String, BoolExpr>> ruleAdd = new HashMap<>();
          Map<String, BoolExpr> add = new HashMap<>();
          add.put("add", inAcl);
          ruleAdd.put("match", add);
          _Tree.get(router).get("pfilter").put(Integer.toString(ruleNum), ruleAdd);
          ruleNum += 1;
          //*/
        }
      }
    }
  }

  /*
   * Initialize boolean expressions to represent that traffic sent along
   * and edge will reach the other side of the edge.
   */
  private void initForwardingAcross() {
    _symbolicDecisions
        .getDataForwarding()
        .forEach(
            (router, edge, var) -> {
              BoolExpr inAcl;
              if (edge.getEnd() == null) {
                inAcl = mkTrue();
              } else {
                GraphEdge ge = getGraph().getOtherEnd().get(edge);
                inAcl = _inboundAcls.get(ge);
                if (inAcl == null) {
                  inAcl = mkTrue();
                }
              }
              _forwardsAcross.put(router, edge, mkAnd(var, inAcl));
            });
  }

  /*
   * Initialize the map of redistributed protocols.
   */
  private void initRedistributionProtocols() {
    for (Entry<String, Configuration> entry : getGraph().getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      for (Protocol proto : getProtocols().get(router)) {
        Set<Protocol> redistributed = new HashSet<>();
        redistributed.add(proto);
        _logicalGraph.getRedistributedProtocols().put(router, proto, redistributed);
        RoutingPolicy pol = Graph.findCommonRoutingPolicy(conf, proto);
        Set<Protocol> unused = new HashSet<Protocol>(getProtocols().get(router));
        unused.remove(proto);
        if (pol != null) {
          Set<Protocol> ps = getGraph().findRedistributedProtocols(conf, pol, proto);
          for (Protocol p : ps) {
            // Make sure there is actually a routing process for the other protocol
            // For example, it might get sliced away if not relevant
            boolean isProto = getProtocols().get(router).contains(p);
            if (isProto) {
              redistributed.add(p);
              unused.remove(p);
            }
          }
        }
        //_softRedistributed.put(router, proto, unused);
      }
      /*System.out.println(" Router: " + router + "\nUsed: " +
       _logicalGraph.getRedistributedProtocols().get(router) + "\nUnused: " +
       _softRedistributed.get(router));*/
    }

  }

  /*
   * Returns are relevant logical edges for a given router and protocol.
   */
  private Set<LogicalEdge> collectAllImportLogicalEdges(
      String router, Configuration conf, Protocol proto) {
    Set<LogicalEdge> eList = new HashSet<>();
    List<ArrayList<LogicalEdge>> les = _logicalGraph.getLogicalEdges().get(router, proto);
    assert (les != null);
    for (ArrayList<LogicalEdge> es : les) {
      for (LogicalEdge le : es) {
        if ( ( _logicalGraph.isEdgeUsed(conf, proto, le) || _ospfAllow.contains(le) ||  _bgpAllow.contains(le) ) && le.getEdgeType() == EdgeType.IMPORT) {
          eList.add(le);
        }
      }
    }
    return eList;
  }

  /*
   * Converts a list of prefixes into a boolean expression that determines
   * if they are relevant for the symbolic packet.
   */
  BoolExpr relevantOrigination(Set<Prefix> prefixes) {
    BoolExpr acc = mkFalse();
    for (Prefix p : prefixes) {
      acc = mkOr(acc, isRelevantFor(p, _symbolicPacket.getDstIp()));
    }
    return acc;
  }

  /*
   * Get the added cost out an interface for a given routing protocol.
   */
  private Integer addedCost(Protocol proto, GraphEdge ge) {
    if (proto.isOspf()) {
      return ge.getStart().getOspfCost();
    }
    return 1;
  }

  /*
   * Determine if an interface is active for a particular protocol.
   */
  private BoolExpr interfaceActive(Interface iface, Protocol proto, LogicalEdge e) {
    BoolExpr active = mkBool(iface.getActive());
    if (proto.isOspf() && !_ospfAllow.contains(e)) {
      active = mkAnd(active, mkBool(iface.getOspfEnabled()));
    }
    return active;
  }

  /*
   * Checks if a prefix could possible be relevant for encoding
   */
  boolean relevantPrefix(Prefix p) {
    if (_headerSpace.getDstIps() == null) {
      return true;
    }
    return new IpSpaceMayIntersectWildcard(new IpWildcard(p), ImmutableMap.of())
        .visit(_headerSpace.getDstIps());
  }

  /*
   * Check if a prefix range match is applicable for the packet destination
   * Ip address, given the prefix length variable.
   */
  BoolExpr isRelevantFor(ArithExpr prefixLen, PrefixRange range) {
    Prefix p = range.getPrefix();
    SubRange r = range.getLengthRange();
    long pfx = p.getStartIp().asLong();
    int len = p.getPrefixLength();
    int lower = r.getStart();
    int upper = r.getEnd();
    // well formed prefix
    assert (p.getPrefixLength() <= lower && lower <= upper);
    BoolExpr lowerBitsMatch = firstBitsEqual(_symbolicPacket.getDstIp(), pfx, len);
    if (lower == upper) {
      BoolExpr equalLen = mkEq(prefixLen, mkInt(lower));
      return mkAnd(equalLen, lowerBitsMatch);
    } else {
      BoolExpr lengthLowerBound = mkGe(prefixLen, mkInt(lower));
      BoolExpr lengthUpperBound = mkLe(prefixLen, mkInt(upper));
      return mkAnd(lengthLowerBound, lengthUpperBound, lowerBitsMatch);
    }
  }

 
 /*
   * Check if a prefix range match is applicable for the packet destination
   * Ip address, given the prefix length variable.
   */
 //* ARCHIE REMOVE
  BoolExpr isRelevantForSoft(ArithExpr prefixLen, PrefixRange range, String routerName) {
    Prefix p = range.getPrefix();
    SubRange r = range.getLengthRange();
    long pfx = p.getStartIp().asLong();
    int len = p.getPrefixLength();
    int lower = r.getStart();
    int upper = r.getEnd();
    // well formed prefix
    assert (p.getPrefixLength() < lower && lower <= upper);
    BoolExpr lowerBitsMatch = firstBitsEqual(_symbolicPacket.getDstIp(), pfx, len);
    if (lower == upper) {
      BoolExpr equalLen = mkEq(prefixLen, mkInt(lower));
      return mkAnd(equalLen, lowerBitsMatch);
    } else {
      BoolExpr lengthLowerBound = mkGe(prefixLen, mkInt(lower));
      BoolExpr lengthUpperBound = mkLe(prefixLen, mkInt(upper));
      if (!(lowerBitsMatch.toString().equals("true") || lowerBitsMatch.toString().equals("false"))) {
        //System.out.println("Lower bits match: " + lowerBitsMatch);
        BoolExpr shouldRemove = getCtx().mkBoolConst(_encoder.getId() + "_"
         + p + routerName + "BGPRemoveFilterSoft");

        Map<String, Map<String, BoolExpr>> ruleRemove = new HashMap<>();
        Map<String, BoolExpr> remove = new HashMap<>();
        remove.put("remove", shouldRemove);
        ruleRemove.put("match", remove);
        _Tree.get(routerName).get("rfilter").put(Integer.toString(exportRuleNum), ruleRemove);
        exportRuleNum = exportRuleNum + 1;
        if (_encoder._repairObjective == 0) {
          addSoft(shouldRemove, importWeight, "BGPRemoveFilter");
        }
        _routerConsMap.put(routerName, mkAnd(_routerConsMap.get(routerName), shouldRemove));
        //BoolExpr shouldAdd = getCtx().mkBoolConst(_encoder.getId() + "_" + prefixLen + "BGPAddFilter");
        //addSoft(mkNot(shouldAdd), 1, "BGPAddFilter");
        //BoolExpr softconst = mkOr(mkAnd(lowerBitsMatch, shouldRemove), shouldAdd);
        BoolExpr softconst = mkAnd(lowerBitsMatch, shouldRemove);
        return mkAnd(lengthLowerBound, lengthUpperBound, softconst);
      } else {
        return mkAnd(lengthLowerBound, lengthUpperBound, lowerBitsMatch);
      }
    }
  }//*/

  private BoolExpr firstBitsEqual(BitVecExpr x, long y, int n) {
    assert (n >= 0 && n <= 32);
    if (n == 0) {
      return mkTrue();
    }
    int m = 0;
    for (int i = 0; i < n; i++) {
      m |= (1 << (31 - i));
    }
    BitVecExpr mask = getCtx().mkBV(m, 32);
    BitVecExpr val = getCtx().mkBV(y, 32);
    return mkEq(getCtx().mkBVAND(x, mask), getCtx().mkBVAND(val, mask));
  }

  BoolExpr isRelevantFor(Prefix p, BitVecExpr be) {
    long pfx = p.getStartIp().asLong();
    return firstBitsEqual(be, pfx, p.getPrefixLength());
  }

  @Nullable
  SymbolicRoute getBestNeighborPerProtocol(String router, Protocol proto) {
    if (_optimizations.getSliceHasSingleProtocol().contains(router)) {
      return getSymbolicDecisions().getBestNeighbor().get(router);
    } else {
      return getSymbolicDecisions().getBestNeighborPerProtocol().get(router, proto);
    }
  }

  /*
   * Initializes the logical graph edges for the protocol-centric view
   */
  private void buildEdgeMap() {
    for (Entry<String, List<Protocol>> entry : getProtocols().entrySet()) {
      String router = entry.getKey();
      if(!_Tree.containsKey(router)) {
        _Tree.put(router, new HashMap<>());
        _Tree.get(router).put("rfilter", new HashMap<>());
        _Tree.get(router).put("pfilter", new HashMap<>());
        _Tree.get(router).put("process", new HashMap<>());
        _Tree.get(router).get("process").put("origination", new HashMap<>());
        _Tree.get(router).get("process").put("adjacency", new HashMap<>());
      }
      for (Protocol p : entry.getValue()) {
        _logicalGraph.getLogicalEdges().put(router, p, new ArrayList<>());
      }
    }
  }

  /*
   * Initialize variables representing if a router chooses
   * to use a particular interface for control-plane forwarding
   */
  private void addChoiceVariables() {
    for (String router : getGraph().getRouters()) {
      Configuration conf = getGraph().getConfigurations().get(router);
      Map<Protocol, Map<LogicalEdge, BoolExpr>> map = new HashMap<>();
      _symbolicDecisions.getChoiceVariables().put(router, map);
      for (Protocol proto : getProtocols().get(router)) {
        Map<LogicalEdge, BoolExpr> edgeMap = new HashMap<>();
        map.put(proto, edgeMap);
        for (LogicalEdge e : collectAllImportLogicalEdges(router, conf, proto)) {
          String chName = e.getSymbolicRecord().getName() + "_choice";
          BoolExpr choiceVar = getCtx().mkBoolConst(chName);
          getAllVariables().put(choiceVar.toString() + _encoder.getStringId(),
           choiceVar);
          edgeMap.put(e, choiceVar);
          _allBoolVars.add(choiceVar);
          Symbol temp = getCtx().mkSymbol(chName);
          _allBoolVarsList.add(temp);

        }
      }
    }
  }

  /*
   * Initalizes variables representing the data plane final forwarding decisions
   */
  private void addDataForwardingVariables() {
    _symbolicDecisions.setDataForwarding();
    Set<BoolExpr> fwdset = new HashSet<>();
    for (Entry<String, List<GraphEdge>> entry : getGraph().getEdgeMap().entrySet()) {
      String router = entry.getKey();
      List<GraphEdge> edges = entry.getValue();
      for (GraphEdge edge : edges) {
        String iface = edge.getStart().getName();
        if (!edge.isAbstract()) {
          String dName =
              _encoder.getId() + "_" + _sliceName + "DATA-FORWARDING_" + router + "_" + iface;
          BoolExpr dForward = getCtx().mkBoolConst(dName);
          getAllVariables().put(dForward.toString() + _encoder.getStringId(),
           dForward);
          _symbolicDecisions.getDataForwarding().put(router, edge, dForward);
          fwdset.add(dForward);
        }
      }
    }
    _encoder._dataforward.put( _encoder.getStringId(), fwdset);  
  }

  /*
   * Initalizes variables representing the control plane and
   * data plane final forwarding decisions
   */
  private void addForwardingVariables() {
    Set<BoolExpr> fwdset = new HashSet<>();
    for (Entry<String, List<GraphEdge>> entry : getGraph().getEdgeMap().entrySet()) {
      String router = entry.getKey();
      List<GraphEdge> edges = entry.getValue();
      for (GraphEdge edge : edges) {
        String iface = edge.getStart().getName();
        String cName =
            _encoder.getId() + "_" + _sliceName + "CONTROL-FORWARDING_" + router + "_" + iface;
        BoolExpr cForward = getCtx().mkBoolConst(cName);
        getAllVariables().put(cForward.toString() + _encoder.getStringId(),
         cForward);
        _symbolicDecisions.getControlForwarding().put(router, edge, cForward);
        _allBoolVars.add(cForward);
        Symbol temp = getCtx().mkSymbol(cName);
        _allBoolVarsList.add(temp);

        // Don't add data forwarding variable for abstract edge
        if (!edge.isAbstract()) {
          String dName =
              _encoder.getId() + "_" + _sliceName + "DATA-FORWARDING_" + router + "_" + iface;
          BoolExpr dForward = getCtx().mkBoolConst(dName);
          getAllVariables().put(dForward.toString() + _encoder.getStringId(),
           dForward);
          fwdset.add(dForward);
          _symbolicDecisions.getDataForwarding().put(router, edge, dForward);
          _allBoolVars.add(dForward);
          Symbol temp2 = getCtx().mkSymbol(dName);
          _allBoolVarsList.add(temp2);

        }
      }
    }
    _encoder._dataforward.put( _encoder.getStringId(), fwdset);  
  }

  /*
   * Initialize variables representing the best choice both for
   * each protocol as well as for the router as a whole
   */
  private void addBestVariables() {
    for (Entry<String, List<Protocol>> entry : getProtocols().entrySet()) {
      String router = entry.getKey();
      List<Protocol> allProtos = entry.getValue();
      // Overall best
      for (int len = 0; len <= BITS; len++) {
        String name =
            String.format(
                "%d_%s%s_%s_%s_%s",
                _encoder.getId(), _sliceName, router, "OVERALL", "BEST", "None");
        String historyName = name + "_history";
        SymbolicEnum<Protocol> h = new SymbolicEnum<>(this, allProtos, historyName);
        if (h.getBitVec()!=null) {
          _allBVVars.add(h.getBitVec());
          _allBVValues.put(h.getBitVec(),h._numBits);
          Symbol temp = getCtx().mkSymbol(historyName);
          _allBVVarsList.add(temp);
          _allBVValuesMap.put(temp, h._numBits);
        }
        SymbolicRoute evBest =
            new SymbolicRoute(this, name, router, Protocol.BEST, _optimizations, h, false);
        getAllSymbolicRecords().add(evBest);
        _symbolicDecisions.getBestNeighbor().put(router, evBest);
      }

      // Best per protocol
      if (!_optimizations.getSliceHasSingleProtocol().contains(router)) {
        for (Protocol proto : getProtocols().get(router)) {
          String name =
              String.format(
                  "%d_%s%s_%s_%s_%s",
                  _encoder.getId(), _sliceName, router, proto.name(), "BEST", "None");
          // String historyName = name + "_history";
          // SymbolicEnum<Protocol> h = new SymbolicEnum<>(this, allProtos, historyName);

          for (int len = 0; len <= BITS; len++) {
            SymbolicRoute evBest =
                new SymbolicRoute(this, name, router, proto, _optimizations, null, false);
            getAllSymbolicRecords().add(evBest);
            _symbolicDecisions.getBestNeighborPerProtocol().put(router, proto, evBest);
          }
        }
      }
    }
  }

  /*
   * Initialize all control-plane message symbolic records.
   * Also maps each logical graph edge to its opposite edge.
   */
  private void addSymbolicRecords() {
    Map<String, Map<Protocol, Map<GraphEdge, ArrayList<LogicalEdge>>>> importInverseMap =
        new HashMap<>();
    Map<String, Map<Protocol, Map<GraphEdge, ArrayList<LogicalEdge>>>> exportInverseMap =
        new HashMap<>();
    Map<String, Map<Protocol, SymbolicRoute>> singleExportMap = new HashMap<>();

    // add edge EXPORT and IMPORT state variables
    for (Entry<String, List<GraphEdge>> entry : getGraph().getEdgeMap().entrySet()) {
      String router = entry.getKey();
      List<GraphEdge> edges = entry.getValue();
      Map<Protocol, SymbolicRoute> singleProtoMap;
      singleProtoMap = new HashMap<>();
      Map<Protocol, Map<GraphEdge, ArrayList<LogicalEdge>>> importEnumMap;
      importEnumMap = new HashMap<>();
      Map<Protocol, Map<GraphEdge, ArrayList<LogicalEdge>>> exportEnumMap;
      exportEnumMap = new HashMap<>();

      singleExportMap.put(router, singleProtoMap);
      importInverseMap.put(router, importEnumMap);
      exportInverseMap.put(router, exportEnumMap);

      for (Protocol proto : getProtocols().get(router)) {

        // Add redistribution variables
        Set<Protocol> r = _logicalGraph.getRedistributedProtocols().get(router, proto);
        assert r != null;
        if (proto.isOspf() && r.size() > 1) {
          // Add the ospf redistributed record if needed
          String rname =
              String.format(
                  "%d_%s%s_%s_%s",
                  _encoder.getId(), _sliceName, router, proto.name(), "Redistributed");
          SymbolicRoute rec =
              new SymbolicRoute(this, rname, router, proto, _optimizations, null, false);
          _ospfRedistributed.put(router, rec);
          getAllSymbolicRecords().add(rec);
        }
        /*
        r = _softRedistributed.get(router, proto);
        assert r != null;
        if (proto.isOspf() && r.size() > 0) {
          // Add the soft ospf redistributed record if needed
          String rname =
              String.format(
                  "%d_%s%s_%s_%s",
                  _encoder.getId(), _sliceName, router, proto.name(), "SoftRedistributed");
          SymbolicRoute rec =
              new SymbolicRoute(this, rname, router, proto, _optimizations, null, false);
          _softOspfRedistributed.put(router, rec);
          getAllSymbolicRecords().add(rec);
        }*/


        Boolean useSingleExport =
            _optimizations.getSliceCanKeepSingleExportVar().get(router, proto);
        assert (useSingleExport != null);

        Map<GraphEdge, ArrayList<LogicalEdge>> importGraphEdgeMap = new HashMap<>();
        Map<GraphEdge, ArrayList<LogicalEdge>> exportGraphEdgeMap = new HashMap<>();

        importEnumMap.put(proto, importGraphEdgeMap);
        exportEnumMap.put(proto, exportGraphEdgeMap);

        for (GraphEdge e : edges) {

          Configuration conf = getGraph().getConfigurations().get(router);
          boolean edgeHasOspf = getGraph().isEdgeUsed(conf, Protocol.OSPF, e);
          boolean edgeHasBGP = getGraph().isEdgeUsed(conf, Protocol.BGP, e);
          if (getGraph().isEdgeUsed(conf, proto, e)) {

            ArrayList<LogicalEdge> importEdgeList = new ArrayList<>();
            ArrayList<LogicalEdge> exportEdgeList = new ArrayList<>();
            importGraphEdgeMap.put(e, importEdgeList);
            exportGraphEdgeMap.put(e, exportEdgeList);

            for (int len = 0; len <= BITS; len++) {

              String ifaceName = e.getStart().getName();

              if (!proto.isConnected() && !proto.isStatic()) {
                // If we use a single set of export variables, then make sure
                // to reuse the existing variables instead of creating new ones
                if (useSingleExport) {
                  SymbolicRoute singleVars = singleExportMap.get(router).get(proto);
                  SymbolicRoute ev1;
                  if (singleVars == null) {
                    String name =
                        String.format(
                            "%d_%s%s_%s_%s_%s",
                            _encoder.getId(),
                            _sliceName,
                            router,
                            proto.name(),
                            "SINGLE-EXPORT",
                            "");
                    ev1 =
                        new SymbolicRoute(
                            this, name, router, proto, _optimizations, null, e.isAbstract());
                    singleProtoMap.put(proto, ev1);
                    getAllSymbolicRecords().add(ev1);

                  } else {
                    ev1 = singleVars;
                  }
                  LogicalEdge eExport = new LogicalEdge(e, EdgeType.EXPORT, ev1);
                  exportEdgeList.add(eExport);

                } else {
                  String name =
                      String.format(
                          "%d_%s%s_%s_%s_%s",
                          _encoder.getId(), _sliceName, router, proto.name(), "EXPORT", ifaceName);

                  SymbolicRoute ev1 =
                      new SymbolicRoute(
                          this, name, router, proto, _optimizations, null, e.isAbstract());
                  LogicalEdge eExport = new LogicalEdge(e, EdgeType.EXPORT, ev1);
                  exportEdgeList.add(eExport);
                  getAllSymbolicRecords().add(ev1);
                }
              }

              boolean notNeeded =
                  _optimizations
                      .getSliceCanCombineImportExportVars()
                      .get(router)
                      .get(proto)
                      .contains(e);

              Interface i = e.getStart();
              Prefix p = i.getAddress().getPrefix();

              boolean doModel = !(proto.isConnected() && p != null && !relevantPrefix(p));
              // PolicyQuotient: Don't model the connected interfaces that aren't relevant
              if (doModel) {
                if (notNeeded) {
                  String name =
                      String.format(
                          "%d_%s%s_%s_%s_%s",
                          _encoder.getId(), _sliceName, router, proto.name(), "IMPORT", ifaceName);
                  SymbolicRoute ev2 = new SymbolicRoute(name, proto);
                  LogicalEdge eImport = new LogicalEdge(e, EdgeType.IMPORT, ev2);
                  importEdgeList.add(eImport);
                } else {
                  String name =
                      String.format(
                          "%d_%s%s_%s_%s_%s",
                          _encoder.getId(), _sliceName, router, proto.name(), "IMPORT", ifaceName);
                  SymbolicRoute ev2 =
                      new SymbolicRoute(
                          this, name, router, proto, _optimizations, null, e.isAbstract());
                  LogicalEdge eImport = new LogicalEdge(e, EdgeType.IMPORT, ev2);
                  importEdgeList.add(eImport);
                  getAllSymbolicRecords().add(ev2);
                }
              }
            }

            List<ArrayList<LogicalEdge>> es = _logicalGraph.getLogicalEdges().get(router, proto);
            assert (es != null);

            ArrayList<LogicalEdge> allEdges = new ArrayList<>();
            allEdges.addAll(importEdgeList);
            allEdges.addAll(exportEdgeList);
            es.add(allEdges);
          } else if (((proto.isOspf() && !edgeHasBGP) || (proto.isBgp() && !edgeHasOspf))
           && e.getStart()!=null && e.getEnd()!=null) {
            //System.out.println(" Edge without OSPF or BGP: " + e);
            /////////////////////////////////////////////////////////////////////////
            

            ArrayList<LogicalEdge> importEdgeList = new ArrayList<>();
            ArrayList<LogicalEdge> exportEdgeList = new ArrayList<>();
            importGraphEdgeMap.put(e, importEdgeList);
            exportGraphEdgeMap.put(e, exportEdgeList);

            for (int len = 0; len <= BITS; len++) {

              String ifaceName = e.getStart().getName();

              if (!proto.isConnected() && !proto.isStatic()) {
                // If we use a single set of export variables, then make sure
                // to reuse the existing variables instead of creating new ones
                if (useSingleExport) {
                  SymbolicRoute singleVars = singleExportMap.get(router).get(proto);
                  SymbolicRoute ev1;
                  if (singleVars == null) {
                    String name =
                        String.format(
                            "%d_%s%s_%s_%s_%s",
                            _encoder.getId(),
                            _sliceName,
                            router,
                            proto.name(),
                            "SINGLE-EXPORT",
                            "");
                    ev1 =
                        new SymbolicRoute(
                            this, name, router, proto, _optimizations, null, e.isAbstract());
                    //addSoft(mkNot(ev1.getPermitted()), 1, "Enable" + name );
                    singleProtoMap.put(proto, ev1);
                    getAllSymbolicRecords().add(ev1);

                  } else {
                    ev1 = singleVars;
                  }
                  LogicalEdge eExport = new LogicalEdge(e, EdgeType.EXPORT, ev1);
                  exportEdgeList.add(eExport);
                  if (proto.isOspf()) {
                    _ospfAllow.add(eExport);
                  } else if (proto.isBgp()) {
                    _bgpAllow.add(eExport);
                  }

                } else {
                  String name =
                      String.format(
                          "%d_%s%s_%s_%s_%s",
                          _encoder.getId(), _sliceName, router, proto.name(), "EXPORT", ifaceName);

                  SymbolicRoute ev1 =
                      new SymbolicRoute(
                          this, name, router, proto, _optimizations, null, e.isAbstract());
                  //addSoft(mkNot(ev1.getPermitted()), 1, "Enable" + name );
                  LogicalEdge eExport = new LogicalEdge(e, EdgeType.EXPORT, ev1);
                  exportEdgeList.add(eExport);
                  if (proto.isOspf()) {
                    _ospfAllow.add(eExport);
                  } else if (proto.isBgp()) {
                    _bgpAllow.add(eExport);
                  }
                  getAllSymbolicRecords().add(ev1);
                }
              }

              boolean notNeeded =
                  _optimizations
                      .getSliceCanCombineImportExportVars()
                      .get(router)
                      .get(proto)
                      .contains(e);

              Interface i = e.getStart();
              Prefix p = i.getAddress().getPrefix();

              boolean doModel = !(proto.isConnected() && p != null && !relevantPrefix(p));
              // Optimization: Don't model the connected interfaces that aren't relevant
              if (doModel) {
                if (notNeeded) {
                  String name =
                      String.format(
                          "%d_%s%s_%s_%s_%s",
                          _encoder.getId(), _sliceName, router, proto.name(), "IMPORT", ifaceName);
                  SymbolicRoute ev2 = new SymbolicRoute(name, proto);
                  //addSoft(mkNot(ev2.getPermitted()), 1, "Enable" + name );
                  LogicalEdge eImport = new LogicalEdge(e, EdgeType.IMPORT, ev2);
                  importEdgeList.add(eImport);
                  if (proto.isOspf()) {
                    _ospfAllow.add(eImport);
                  } else if (proto.isBgp()) {
                    _bgpAllow.add(eImport);
                  }
                } else {
                  String name =
                      String.format(
                          "%d_%s%s_%s_%s_%s",
                          _encoder.getId(), _sliceName, router, proto.name(), "IMPORT", ifaceName);
                  SymbolicRoute ev2 =
                      new SymbolicRoute(
                          this, name, router, proto, _optimizations, null, e.isAbstract());
                  //addSoft(mkNot(ev2.getPermitted()), 1, "Enable" + name );
                  LogicalEdge eImport = new LogicalEdge(e, EdgeType.IMPORT, ev2);
                  importEdgeList.add(eImport);
                  if (proto.isOspf()) {
                    _ospfAllow.add(eImport);
                  } else if (proto.isBgp()) {
                    _bgpAllow.add(eImport);
                  }
                  getAllSymbolicRecords().add(ev2);
                }
              }
            }

            List<ArrayList<LogicalEdge>> es = _logicalGraph.getLogicalEdges().get(router, proto);
            assert (es != null);

            ArrayList<LogicalEdge> allEdges = new ArrayList<>();
            allEdges.addAll(importEdgeList);
            allEdges.addAll(exportEdgeList);
            es.add(allEdges);


            /////////////////////////////////////////////////////////////////////////

          }
        }
      }
      //System.out.println("Soft Ospf " + router + " = " + _softOspfRedistributed.get(router));  
    }

    // Build a map to find the opposite of a given edge
    _logicalGraph
        .getLogicalEdges()
        .forEach(
            (router, edgeLists) -> {
              for (Protocol proto : getProtocols().get(router)) {

                for (ArrayList<LogicalEdge> edgeList : edgeLists.get(proto)) {

                  for (LogicalEdge e : edgeList) {

                    GraphEdge edge = e.getEdge();
                    Map<GraphEdge, ArrayList<LogicalEdge>> m;

                    // System.out.println("i: " + i);
                    // System.out.println("proto: " + proto.name());
                    // System.out.println("edge: " + edge + ", " + e.getEdgeType());

                    if (edge.getPeer() != null) {

                      if (e.getEdgeType() == EdgeType.IMPORT) {
                        m = exportInverseMap.get(edge.getPeer()).get(proto);

                      } else {
                        m = importInverseMap.get(edge.getPeer()).get(proto);
                      }

                      if (m != null) {
                        GraphEdge otherEdge = getGraph().getOtherEnd().get(edge);
                        ArrayList<LogicalEdge> list = m.get(otherEdge);
                        if (list == null) {
                          m.put(otherEdge, new ArrayList<>());
                        } else if (list.size() > 0) {
                          LogicalEdge other = list.get(0);
                          _logicalGraph.getOtherEnd().put(e, other);
                        }
                      }
                    }
                  }
                }
              }
            });
  }

  /*
   * Initialize the optimizations object, which computes all
   * applicable optimizations for the current encoding slice.
   */
  private void initOptimizations() {
    _optimizations.computeOptimizations();
    getLocalPref();
  }


  private void getLocalPref() {
    getGraph()
        .getConfigurations()
        .forEach(
            (router, conf) ->
                conf.getRoutingPolicies()
                    .forEach(
                        (name, pol) -> {
                          AstVisitor v = new AstVisitor();
                          v.visit(
                              conf,
                              pol.getStatements(),
                              stmt -> {
                                if (stmt instanceof SetLocalPreference) {
                                  SetLocalPreference slp = (SetLocalPreference) stmt;
                                  IntExpr e = slp.getLocalPreference();
                                  if (e instanceof LiteralInt) {
                                    LiteralInt z = (LiteralInt) e;
                                    _localPref.add(z.getValue());
                                  }
                                }
                              },
                              expr -> {});
                        }));
  }

  private void initOriginatedPrefixes() {
    for (Entry<String, Configuration> entry : getGraph().getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      
      Set<Prefix> allprefixes = Graph.getOriginatedNetworks(conf, Protocol.CONNECTED);
      /*
      // @archie allprefixes has all prefixes orignating at theis router
 
      for (Protocol proto : _optimizations.getProtocols().get(router)) {
        Set<Prefix> tempprefixes = Graph.getOriginatedNetworks(conf, proto);
        allprefixes.addAll(tempprefixes);
        System.out.println("Proto " + proto + " " + tempprefixes);
      }*/

      for (Protocol proto : _optimizations.getProtocols().get(router)) {
        Set<Prefix> prefixes = Graph.getOriginatedNetworks(conf, proto);
        _originatedNetworks.put(router, proto, prefixes);
        Set<Prefix> tempPrefixes = new HashSet<>();
        tempPrefixes.addAll(allprefixes);
        tempPrefixes.removeAll(prefixes);
        _allOriginatedNetworks.put(router, proto, tempPrefixes);
      }
    }
  }

  /*
   * Check if this is the main slice
   */
  boolean isMainSlice() {
    return _sliceName.equals("") || _sliceName.equals(Encoder.MAIN_SLICE_NAME);
  }

  /*
   * Initialize all environment symbolic records for BGP.
   */
  private void addEnvironmentVariables() {
    // If not the main slice, just use the main slice
    if (!isMainSlice()) {
      Map<LogicalEdge, SymbolicRoute> envs = _logicalGraph.getEnvironmentVars();
      EncoderSlice main = _encoder.getMainSlice();
      LogicalGraph lg = main.getLogicalGraph();
      Map<LogicalEdge, SymbolicRoute> existing = lg.getEnvironmentVars();
      envs.putAll(existing);
      return;
    }

    // Otherwise create it anew
    for (String router : getGraph().getRouters()) {
      for (Protocol proto : getProtocols().get(router)) {
        if (proto.isBgp()) {
          List<ArrayList<LogicalEdge>> les = _logicalGraph.getLogicalEdges().get(router, proto);
          assert (les != null);
          for (ArrayList<LogicalEdge> eList : les) {
            for (LogicalEdge e : eList) {
              if (e.getEdgeType() == EdgeType.IMPORT) {
                GraphEdge ge = e.getEdge();
                BgpNeighbor n = getGraph().getEbgpNeighbors().get(ge);
                if (n != null && ge.getEnd() == null) {

                  if (!isMainSlice()) {
                    LogicalGraph lg = _encoder.getMainSlice().getLogicalGraph();
                    SymbolicRoute r = lg.getEnvironmentVars().get(e);
                    _logicalGraph.getEnvironmentVars().put(e, r);
                  } else {
                    String address;
                    if (n.getAddress() == null) {
                      address = "null";
                    } else {
                      address = n.getAddress().toString();
                    }
                    String ifaceName = "ENV-" + address;
                    String name =
                        String.format(
                            "%d_%s%s_%s_%s_%s",
                            _encoder.getId(),
                            _sliceName,
                            router,
                            proto.name(),
                            "EXPORT",
                            ifaceName);
                    SymbolicRoute vars =
                        new SymbolicRoute(
                            this, name, router, proto, _optimizations, null, ge.isAbstract());
                    getAllSymbolicRecords().add(vars);
                    _logicalGraph.getEnvironmentVars().put(e, vars);
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  /*
   * Initialize all symbolic variables for the encoding slice
   */
  private void initVariables() {
    buildEdgeMap();
    addForwardingVariables();
    addBestVariables();
    addSymbolicRecords();
    addChoiceVariables();
    addEnvironmentVariables();
  }


private void addSymbolicPacketBoundConstraints() {

    ArithExpr upperBound4 = mkInt((long) Math.pow(2, 4));
    ArithExpr upperBound8 = mkInt((long) Math.pow(2, 8));
    ArithExpr upperBound16 = mkInt((long) Math.pow(2, 16));
    ArithExpr upperBound32 = mkInt((long) Math.pow(2, 32));
    ArithExpr zero = mkInt(0);

    // Valid 16 bit integer
    add(mkGe(_symbolicPacket.getDstPort(), zero));
    add(mkGe(_symbolicPacket.getSrcPort(), zero));
    add(mkLt(_symbolicPacket.getDstPort(), upperBound16));
    add(mkLt(_symbolicPacket.getSrcPort(), upperBound16));

    // Valid 8 bit integer
    add(mkGe(_symbolicPacket.getIcmpType(), zero));
    add(mkGe(_symbolicPacket.getIpProtocol(), zero));
    add(mkLt(_symbolicPacket.getIcmpType(), upperBound8));
    add(mkLe(_symbolicPacket.getIpProtocol(), upperBound8));

    // Valid 4 bit integer
    add(mkGe(_symbolicPacket.getIcmpCode(), zero));
    add(mkLt(_symbolicPacket.getIcmpCode(), upperBound4));
}

  /*
   * Constraint each variable to fall within a valid range.
   * Metric cost is protocol dependent and need not have an
   * upper bound, since overflow is modeled.
   *
   * dstIp, srcIp:        [0,2^32)
   * dstPort, srcPort:    [0,2^16)
   * icmpType, protocol:  [0,2^8)
   * icmpCode:            [0,2^4)
   *
   * prefix length:       [0,2^32)
   * admin distance:      [0,2^8)
   * BGP med, local-pref: [0,2^32)
   * metric cost:         [0,2^8) if environment
   * metric cost:         [0,2^16) otherwise
   */
  private void addBoundConstraints() {

    ArithExpr upperBound4 = mkInt((long) Math.pow(2, 4));
    ArithExpr upperBound8 = mkInt((long) Math.pow(2, 8));
    ArithExpr upperBound16 = mkInt((long) Math.pow(2, 16));
    ArithExpr upperBound32 = mkInt((long) Math.pow(2, 32));
    ArithExpr zero = mkInt(0);

    // Valid 16 bit integer
    add(mkGe(_symbolicPacket.getDstPort(), zero));
    add(mkGe(_symbolicPacket.getSrcPort(), zero));
    add(mkLt(_symbolicPacket.getDstPort(), upperBound16));
    add(mkLt(_symbolicPacket.getSrcPort(), upperBound16));

    // Valid 8 bit integer
    add(mkGe(_symbolicPacket.getIcmpType(), zero));
    add(mkGe(_symbolicPacket.getIpProtocol(), zero));
    add(mkLt(_symbolicPacket.getIcmpType(), upperBound8));
    add(mkLe(_symbolicPacket.getIpProtocol(), upperBound8));

    // Valid 4 bit integer
    add(mkGe(_symbolicPacket.getIcmpCode(), zero));
    add(mkLt(_symbolicPacket.getIcmpCode(), upperBound4));

    for (SymbolicRoute e : getAllSymbolicRecords()) {
      if (e.getRouterId() != null) {
        add(mkGe(e.getRouterId(), zero));
      }

      if (e.getAdminDist() != null) {
        add(mkGe(e.getAdminDist(), zero));
        add(mkLt(e.getAdminDist(), upperBound8));
      }
      if (e.getMed() != null) {
        add(mkGe(e.getMed(), zero));
        add(mkLt(e.getMed(), upperBound32));
      }
      if (e.getLocalPref() != null) {
        add(mkGe(e.getLocalPref(), zero));
        add(mkLt(e.getLocalPref(), upperBound32));
      }
      if (e.getMetric() != null) {
        add(mkGe(e.getMetric(), zero));
        if (e.isEnv()) {
          add(mkLt(e.getMetric(), upperBound8));
        }
        add(mkLt(e.getMetric(), upperBound16));
      }
      if (e.getIgpMetric() != null) {
        add(mkGe(e.getIgpMetric(), zero));
      }
      if (e.getPrefixLength() != null) {
        add(mkGe(e.getPrefixLength(), zero));
        add(mkLe(e.getPrefixLength(), mkInt(32)));
      }
    }
  }

  /*
   * Constraints each community regex match. A regex match
   * will be true, if either one of its subsumed exact values is
   * attached to the message, or some other community that matches
   * the regex is instead:
   *
   * c_regex = (c_1 or ... or c_n) or c_other
   *
   * where the regex matches c_i. The regex match is determined
   * ahead of time based on the configuration.
   */
  private void addCommunityConstraints() {
    for (SymbolicRoute r : getAllSymbolicRecords()) {
      for (Entry<CommunityVar, BoolExpr> entry : r.getCommunities().entrySet()) {
        CommunityVar cvar = entry.getKey();
        BoolExpr e = entry.getValue();
        if (cvar.getType() == CommunityVar.Type.REGEX) {
          BoolExpr acc = mkFalse();
          List<CommunityVar> deps = getGraph().getCommunityDependencies().get(cvar);
          for (CommunityVar dep : deps) {
            BoolExpr depExpr = r.getCommunities().get(dep);
            acc = mkOr(acc, depExpr);
          }
          BoolExpr regex = mkEq(acc, e);
          add(regex);
        }
      }
    }
  }

  /*
   * A collection of default values to fill in missing values for
   * comparison. These are needed because the optimizations might
   * remove various attributes from messages when unnecessary.
   */

  ArithExpr defaultAdminDistance(Configuration conf, Protocol proto, SymbolicRoute r) {
    ArithExpr def = mkInt(defaultAdminDistance(conf, proto));
    if (r.getBgpInternal() == null) {
      return def;
    }
    return mkIf(r.getBgpInternal(), mkInt(200), def);
  }

  private int defaultAdminDistance(Configuration conf, Protocol proto) {
    RoutingProtocol rp = Protocol.toRoutingProtocol(proto);
    return rp.getDefaultAdministrativeCost(conf.getConfigurationFormat());
  }

  int defaultId() {
    return 0;
  }

  int defaultMetric() {
    return 0;
  }

  int defaultMed(Protocol proto) {
    if (proto.isBgp()) {
      return 100;
    }
    return 0;
  }

  int defaultLocalPref() {
    return 100;
  }

  int defaultLength() {
    return 0;
  }

  private int defaultIgpMetric() {
    return 0;
  }

  private BitVecExpr defaultOspfType() {
    return getCtx().mkBV(0, 2); // OI
  }

  /*
   * Returns the symbolic record for logical edge e.
   * This method is necessary, because optimizations might
   * decide that certain records can be merged together.
   */
  SymbolicRoute correctVars(LogicalEdge e) {
    SymbolicRoute vars = e.getSymbolicRecord();
    if (!vars.getIsUsed()) {
      return _logicalGraph.getOtherEnd().get(e).getSymbolicRecord();
    }
    return vars;
  }

  /*
   * Creates a symbolic test between a record representing the best
   * field and another field (vars). If the vars field is missing,
   * then the value is filled in with the default value.
   *
   * An assumption is that if best != null, then vars != null
   */
  private BoolExpr equalHelper(Expr best, Expr vars, Expr defaultVal) {
    BoolExpr tru = mkTrue();
    if (vars == null) {
      if (best != null) {
        return mkEq(best, defaultVal);
      } else {
        return tru;
      }
    } else {
      return mkEq(best, vars);
    }
  }

  /*
   * Creates a test to check for equal protocol histories
   * after accounting for null values introduced by optimizations
   */
  BoolExpr equalHistories(SymbolicRoute best, SymbolicRoute vars) {
    BoolExpr history;
    if (best.getProtocolHistory() == null) {
      history = mkTrue();
    } else {
      if (vars.getProtocolHistory() == null) {
        history = best.getProtocolHistory().checkIfValue(vars.getProto());
      } else {
        history = best.getProtocolHistory().mkEq(vars.getProtocolHistory());
      }
    }
    /* if (best.getProtocolHistory() == null || vars.getProtocolHistory() == null) {
        history = mkTrue();
    } else {
        history = best.getProtocolHistory().mkEq(vars.getProtocolHistory());
    } */
    return history;
  }

  /*
   * Creates a test to check for equal bgp internal
   * tags after accounting for null values introduced by optimizations
   */
  private BoolExpr equalBgpInternal(SymbolicRoute best, SymbolicRoute vars) {
    if (best.getBgpInternal() == null || vars.getBgpInternal() == null) {
      return mkTrue();
    } else {
      return mkEq(best.getBgpInternal(), vars.getBgpInternal());
    }
  }

  /*
   * Creates a test to check for equal bgp client id tags after
   * accounting for the possibility of null values.
   */
  private BoolExpr equalClientIds(String router, SymbolicRoute best, SymbolicRoute vars) {
    if (best.getClientId() == null) {
      return mkTrue();
    } else {
      if (vars.getClientId() == null) {
        // Lookup the actual originator id
        Integer i = getGraph().getOriginatorId().get(router);
        if (i == null) {
          return best.getClientId().checkIfValue(0);
        } else {
          return best.getClientId().checkIfValue(i);
        }
      } else {
        return best.getClientId().mkEq(vars.getClientId());
      }
    }
  }

  /*
   * Creates a test to check for equal ospf areas
   * tags after accounting for null values introduced by optimizations
   */
  private BoolExpr equalAreas(SymbolicRoute best, SymbolicRoute vars, @Nullable LogicalEdge e) {
    BoolExpr equalOspfArea;
    boolean hasBestArea = (best.getOspfArea() != null && best.getOspfArea().getBitVec() != null);
    boolean hasVarsArea = (vars.getOspfArea() != null && vars.getOspfArea().getBitVec() != null);
    if (e != null) {
      if (hasBestArea) {
        Interface iface = e.getEdge().getStart();
        if (hasVarsArea) {
          equalOspfArea = best.getOspfArea().mkEq(vars.getOspfArea());
        } else if (iface.getOspfAreaName() != null) {
          equalOspfArea = best.getOspfArea().checkIfValue(iface.getOspfAreaName());
        } else {
          equalOspfArea = best.getOspfArea().isDefaultValue();
        }
      } else {
        equalOspfArea = mkTrue();
      }
    } else {
      equalOspfArea = mkTrue();
    }
    return equalOspfArea;
  }

  /*
   * Creates a symbolic test to check for equal ospf types (OI, OIA, E1, E2)
   * after accounting for null values introduced by optimizations
   */
  private BoolExpr equalTypes(SymbolicRoute best, SymbolicRoute vars) {
    BoolExpr equalOspfType;
    boolean hasBestType = (best.getOspfType() != null && best.getOspfType().getBitVec() != null);
    boolean hasVarsType = (vars.getOspfType() != null && vars.getOspfType().getBitVec() != null);
    if (hasVarsType) {
      equalOspfType = best.getOspfType().mkEq(vars.getOspfType());
    } else if (hasBestType) {
      equalOspfType = best.getOspfType().isDefaultValue();
    } else {
      equalOspfType = mkTrue();
    }
    return equalOspfType;
  }

  /*
   * Creates a symbolic test to check for equal router IDs
   * after accounting for null values introduced by optimizations
   */
  private BoolExpr equalIds(
      SymbolicRoute best, SymbolicRoute vars, Protocol proto, @Nullable LogicalEdge e) {
    BoolExpr equalId;
    if (vars.getRouterId() == null) {
      if (best.getRouterId() == null || e == null) {
        equalId = mkTrue();
      } else {
        long peerId = getGraph().findRouterId(e.getEdge(), proto);
        equalId = mkEq(best.getRouterId(), mkInt(peerId));
      }
    } else {
      equalId = mkEq(best.getRouterId(), vars.getRouterId());
    }
    return equalId;
  }

  private BoolExpr equalCommunities(SymbolicRoute best, SymbolicRoute vars) {
    BoolExpr acc = mkTrue();
    for (Map.Entry<CommunityVar, BoolExpr> entry : best.getCommunities().entrySet()) {
      CommunityVar cvar = entry.getKey();
      BoolExpr var = entry.getValue();
      BoolExpr other = vars.getCommunities().get(cvar);
      if (other == null) {
        acc = mkAnd(acc, mkNot(var));
      } else {
        acc = mkAnd(acc, mkEq(var, other));
      }
    }
    return acc;
  }

  /*
   * Check for equality of a (best) symbolic record and another
   * symbolic record (vars). It checks pairwise that all fields
   * are equal, while filling in values missing due to optimizations
   * with default values based on the protocol.
   * If there is no corresponding edge e, then the value null can be used
   */
  public BoolExpr equal(
      Configuration conf,
      Protocol proto,
      SymbolicRoute best,
      SymbolicRoute vars,
      @Nullable LogicalEdge e,
      boolean compareCommunities) {

    ArithExpr defaultLocal = mkInt(defaultLocalPref());
    ArithExpr defaultAdmin = defaultAdminDistance(conf, proto, vars);
    ArithExpr defaultMet = mkInt(defaultMetric());
    ArithExpr defaultMed = mkInt(defaultMed(proto));
    ArithExpr defaultLen = mkInt(defaultLength());
    ArithExpr defaultIgp = mkInt(defaultIgpMetric());

    BoolExpr equalLen;
    BoolExpr equalAd;
    BoolExpr equalLp;
    BoolExpr equalMet;
    BoolExpr equalMed;
    BoolExpr equalOspfArea;
    BoolExpr equalOspfType;
    BoolExpr equalId;
    BoolExpr equalHistory;
    BoolExpr equalBgpInternal;
    BoolExpr equalClientIds;
    BoolExpr equalIgpMet;
    BoolExpr equalCommunities;

    equalLen = equalHelper(best.getPrefixLength(), vars.getPrefixLength(), defaultLen);
    equalAd = equalHelper(best.getAdminDist(), vars.getAdminDist(), defaultAdmin);
    equalLp = equalHelper(best.getLocalPref(), vars.getLocalPref(), defaultLocal);
    equalMet = equalHelper(best.getMetric(), vars.getMetric(), defaultMet);
    equalMed = equalHelper(best.getMed(), vars.getMed(), defaultMed);
    equalIgpMet = equalHelper(best.getIgpMetric(), vars.getIgpMetric(), defaultIgp);

    equalOspfType = equalTypes(best, vars);
    equalOspfArea = equalAreas(best, vars, e);

    equalId = equalIds(best, vars, proto, e);
    equalHistory = equalHistories(best, vars);
    equalBgpInternal = equalBgpInternal(best, vars);
    equalClientIds = equalClientIds(conf.getName(), best, vars);
    equalCommunities = (compareCommunities ? equalCommunities(best, vars) : mkTrue());

    return mkAnd(
        equalLen,
        equalAd,
        equalLp,
        equalMet,
        equalMed,
        equalOspfArea,
        equalOspfType,
        equalId,
        equalHistory,
        equalBgpInternal,
        equalClientIds,
        equalIgpMet,
        equalCommunities);
  }

  /*
   * Helper function to check if one expression is greater than
   * another accounting for null values introduced by optimizations.
   */
  private BoolExpr geBetterHelper(
      @Nullable Expr best, @Nullable Expr vars, Expr defaultVal, boolean less) {
    BoolExpr fal = mkFalse();
    if (vars == null) {
      if (best != null) {
        if (less) {
          return mkLt(best, defaultVal);
        } else {
          return mkGt(best, defaultVal);
        }
      } else {
        return fal;
      }
    } else {
      assert (best != null);
      if (less) {
        return mkLt(best, vars);
      } else {
        return mkGt(best, vars);
      }
    }
  }

  /*
   * Helper function to check if one expression is equal to
   * another accounting for null values introduced by optimizations.
   */
  private BoolExpr geEqualHelper(@Nullable Expr best, @Nullable Expr vars, Expr defaultVal) {
    if (vars == null) {
      if (best != null) {
        return mkEq(best, defaultVal);
      } else {
        return mkTrue();
      }
    } else {
      assert (best != null);
      return mkEq(best, vars);
    }
  }

  /*
   * Check if a (best) symbolic record is better than another
   * symbolic record (vars). This is done using a recursive lexicographic
   * encoding. The encoding is as follows:
   *
   * (best.length > vars.length) or
   * (best.length = vars.length) and (
   *    (best.adminDist < vars.adminDist) or
   *    (best.adminDist = vars.adminDist) and (
   *     ...
   *    )
   * )
   *
   * This recursive encoding introduces a new variable for each subexpressions,
   * which ends up being much more efficient than expanding out options.
   */
  private BoolExpr greaterOrEqual(
      Configuration conf,
      Protocol proto,
      SymbolicRoute best,
      SymbolicRoute vars,
      @Nullable LogicalEdge e) {

    ArithExpr defaultLocal = mkInt(defaultLocalPref());
    ArithExpr defaultAdmin = defaultAdminDistance(conf, proto, vars);
    ArithExpr defaultMet = mkInt(defaultMetric());
    ArithExpr defaultMed = mkInt(defaultMed(proto));
    ArithExpr defaultLen = mkInt(defaultLength());
    ArithExpr defaultIgp = mkInt(defaultIgpMetric());
    ArithExpr defaultId = mkInt(0);
    BitVecExpr defaultOspfType = defaultOspfType();

    BoolExpr betterLen =
        geBetterHelper(best.getPrefixLength(), vars.getPrefixLength(), defaultLen, false);
    BoolExpr equalLen = geEqualHelper(best.getPrefixLength(), vars.getPrefixLength(), defaultLen);

    BoolExpr betterAd =
        geBetterHelper(best.getAdminDist(), vars.getAdminDist(), defaultAdmin, true);
    BoolExpr equalAd = geEqualHelper(best.getAdminDist(), vars.getAdminDist(), defaultAdmin);

    BoolExpr betterLp =
        geBetterHelper(best.getLocalPref(), vars.getLocalPref(), defaultLocal, false);
    BoolExpr equalLp = geEqualHelper(best.getLocalPref(), vars.getLocalPref(), defaultLocal);

    BoolExpr betterMet = geBetterHelper(best.getMetric(), vars.getMetric(), defaultMet, true);
    BoolExpr equalMet = geEqualHelper(best.getMetric(), vars.getMetric(), defaultMet);

    BoolExpr betterMed = geBetterHelper(best.getMed(), vars.getMed(), defaultMed, true);
    BoolExpr equalMed = geEqualHelper(best.getMed(), vars.getMed(), defaultMed);

    BitVecExpr bestType = (best.getOspfType() == null ? null : best.getOspfType().getBitVec());
    BitVecExpr varsType = (vars.getOspfType() == null ? null : vars.getOspfType().getBitVec());

    BoolExpr betterOspfType = geBetterHelper(bestType, varsType, defaultOspfType, true);
    BoolExpr equalOspfType = geEqualHelper(bestType, varsType, defaultOspfType);

    BoolExpr betterInternal =
        geBetterHelper(best.getBgpInternal(), vars.getBgpInternal(), mkFalse(), true);
    BoolExpr equalInternal = geEqualHelper(best.getBgpInternal(), vars.getBgpInternal(), mkFalse());

    BoolExpr betterIgpMet =
        geBetterHelper(best.getIgpMetric(), vars.getIgpMetric(), defaultIgp, true);
    BoolExpr equalIgpMet = geEqualHelper(best.getIgpMetric(), vars.getIgpMetric(), defaultIgp);

    BoolExpr tiebreak;

    if (vars.getRouterId() != null) {
      tiebreak = mkLe(best.getRouterId(), vars.getRouterId());
    } else if (best.getRouterId() != null) {
      if (e == null) {
        tiebreak = mkLe(best.getRouterId(), defaultId);
      } else {
        long peerId = getGraph().findRouterId(e.getEdge(), proto);
        tiebreak = mkLe(best.getRouterId(), mkInt(peerId));
      }
    } else {
      tiebreak = mkTrue();
    }

    BoolExpr b = mkAnd(equalOspfType, tiebreak);
    BoolExpr b1 = mkOr(betterOspfType, b);
    BoolExpr b2 = mkAnd(equalIgpMet, b1);
    BoolExpr b3 = mkOr(betterIgpMet, b2);
    BoolExpr b4 = mkAnd(equalInternal, b3);
    BoolExpr b5 = mkOr(betterInternal, b4);
    BoolExpr b6 = mkAnd(equalMed, b5);
    BoolExpr b7 = mkOr(betterMed, b6);
    BoolExpr b8 = mkAnd(equalMet, b7);
    BoolExpr b9 = mkOr(betterMet, b8);
    BoolExpr b10 = mkAnd(equalLp, b9);
    BoolExpr b11 = mkOr(betterLp, b10);
    BoolExpr b12 = mkAnd(equalAd, b11);
    BoolExpr b13 = mkOr(betterAd, b12);
    BoolExpr b14 = mkAnd(equalLen, b13);
    return mkOr(betterLen, b14);
  }

  /*
   * Constraints that specify that the best choice is
   * better than all alternatives, and is at least one of the choices:
   *
   * (1) if no options are valid, then best is not valid
   * (2) if some option is valid, then we have the following:
   *
   * (best <= best_prot1) and ... and (best <= best_protn)
   * (best =  best_prot1) or  ... or  (best =  best_protn)
   */
  private void addBestOverallConstraints() {
    for (Entry<String, Configuration> entry : getGraph().getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      // These constraints will be added at the protocol-level when a single protocol
      if (!_optimizations.getSliceHasSingleProtocol().contains(router)) {

        boolean someProto = false;

        BoolExpr acc = null;
        BoolExpr somePermitted = null;
        SymbolicRoute best = _symbolicDecisions.getBestNeighbor().get(router);

        for (Protocol proto : getProtocols().get(router)) {
          someProto = true;

          SymbolicRoute bestVars = _symbolicDecisions.getBestVars(_optimizations, router, proto);
          assert (bestVars != null);

          if (somePermitted == null) {
            somePermitted = bestVars.getPermitted();
          } else {
            somePermitted = mkOr(somePermitted, bestVars.getPermitted());
          }

          BoolExpr val =
              mkAnd(bestVars.getPermitted(), equal(conf, proto, best, bestVars, null, true));
          if (acc == null) {
            acc = val;
          } else {
            acc = mkOr(acc, val);
          }
          add(
              mkImplies(
                  bestVars.getPermitted(), greaterOrEqual(conf, proto, best, bestVars, null)));
        }

        if (someProto) {
          if (acc != null) {
            add(mkEq(somePermitted, best.getPermitted()));
            add(mkImplies(somePermitted, acc));
          }
        } else {
          add(mkNot(best.getPermitted()));
        }
      }
    }
  }

  /*
   * Constrains each protocol-best record similarly to the overall
   * best record. It will be better than all choices and equal to
   * at least one of them.
   */
  private void addBestPerProtocolConstraints() {
    for (Entry<String, Configuration> entry : getGraph().getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();

      for (Protocol proto : getProtocols().get(router)) {

        SymbolicRoute bestVars = _symbolicDecisions.getBestVars(_optimizations, router, proto);
        assert (bestVars != null);

        BoolExpr acc = null;
        BoolExpr somePermitted = null;

        for (LogicalEdge e : collectAllImportLogicalEdges(router, conf, proto)) {

          SymbolicRoute vars = correctVars(e);

          if (somePermitted == null) {
            somePermitted = vars.getPermitted();
          } else {
            somePermitted = mkOr(somePermitted, vars.getPermitted());
          }

          BoolExpr v = mkAnd(vars.getPermitted(), equal(conf, proto, bestVars, vars, e, true));
          if (acc == null) {
            acc = v;
          } else {
            acc = mkOr(acc, v);
          }
          add(mkImplies(vars.getPermitted(), greaterOrEqual(conf, proto, bestVars, vars, e)));
        }

        if (acc != null) {
          add(mkEq(somePermitted, bestVars.getPermitted()));
          add(mkImplies(somePermitted, acc));
        }
      }
    }
  }

  /*
   * Constraints that define a choice for a given protocol
   * to be when a particular import is equal to the best choice.
   * For example:
   *
   * choice_bgp_Serial0 = (import_Serial0 = best_bgp)
   */
  private void addChoicePerProtocolConstraints() {
    for (Entry<String, Configuration> entry : getGraph().getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      int ruleNum = 0;
      for (Protocol proto : getProtocols().get(router)) {
        SymbolicRoute bestVars = _symbolicDecisions.getBestVars(_optimizations, router, proto);
        assert (bestVars != null);
        for (LogicalEdge e : collectAllImportLogicalEdges(router, conf, proto)) {
          SymbolicRoute vars = correctVars(e);
          BoolExpr choice = _symbolicDecisions.getChoiceVariables().get(router, proto, e);
          assert (choice != null);
          BoolExpr isBest = equal(conf, proto, bestVars, vars, e, false);
          //* ARCHIE REMOVE
          if (_bgpAllow.contains(e) || _ospfAllow.contains(e)) {
 
            BoolExpr shouldAllow;
            String nameOfLE = e.getSymbolicRecord().getName();
            String keyvalue = nameOfLE.substring(nameOfLE.indexOf(_sliceName) + _sliceName.length() + 2) ;
            //System.out.println("Key = " + keyvalue + "  for  " + nameOfLE);
            
            if (_enableRoute.containsKey(keyvalue)) {
              shouldAllow = _enableRoute.get(keyvalue);
            } else {
              shouldAllow = getCtx().mkBoolConst(_encoder.getId() + "_"
                + keyvalue + "AllowChoiceSoft");
              if (_encoder._repairObjective == 0) {
                addSoft(mkNot(shouldAllow), enableAdjWeight, "AllowRoute");
              }
              _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), mkNot(shouldAllow)));
              _enableRoute.put(keyvalue, shouldAllow);
            }

            Map<String, BoolExpr> add = new HashMap<>();
            add.put("add", shouldAllow);
            _Tree.get(router).get("process").get("adjacency").put(
              "static"+Integer.toString(ruleNum), add);
            ruleNum += 1;
            //BoolExpr shouldAllow = getCtx().mkBoolConst(_encoder.getId() + "_"
            //  + vars.getName() + "AllowChoice");
            //addSoft(mkNot(shouldAllow), 1, "AllowRoute");

            isBest = mkAnd(isBest,shouldAllow);
          }//*/
          add(mkEq(choice, mkAnd(vars.getPermitted(), isBest)));
        }
      }
    }
  }

  /*
   * Constraints that define control-plane forwarding.
   * If there is some valid import, then control plane forwarding
   * will occur out an interface when this is the best choice.
   * Otherwise, it will not occur.
   */
  private void addControlForwardingConstraints() {
    for (Entry<String, Configuration> entry : getGraph().getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      boolean someEdge = false;

      SymbolicRoute best = _symbolicDecisions.getBestNeighbor().get(router);
      Map<GraphEdge, BoolExpr> cfExprs = new HashMap<>();

      Set<GraphEdge> constrained = new HashSet<>();

      for (Protocol proto : getProtocols().get(router)) {

        for (LogicalEdge e : collectAllImportLogicalEdges(router, conf, proto)) {

          someEdge = true;
          constrained.add(e.getEdge());

          SymbolicRoute vars = correctVars(e);
          BoolExpr choice = _symbolicDecisions.getChoiceVariables().get(router, proto, e);
          BoolExpr isBest = mkAnd(choice, equal(conf, proto, best, vars, e, false));

          GraphEdge ge = e.getEdge();

          // Connected routes should only forward if not absorbed by interface
          GraphEdge other = getGraph().getOtherEnd().get(ge);
          BoolExpr connectedWillSend;
          if (other == null || getGraph().isHost(ge.getPeer())) {
            Ip ip = ge.getStart().getAddress().getIp();
            BitVecExpr val = getCtx().mkBV(ip.asLong(), 32);
            connectedWillSend = mkNot(mkEq(_symbolicPacket.getDstIp(), val));
          } else {
            Ip ip = other.getStart().getAddress().getIp();
            BitVecExpr val = getCtx().mkBV(ip.asLong(), 32);
            connectedWillSend = mkEq(_symbolicPacket.getDstIp(), val);
          }
          BoolExpr canSend = (proto.isConnected() ? connectedWillSend : mkTrue());

          BoolExpr sends = mkAnd(canSend, isBest);

          BoolExpr cForward = _symbolicDecisions.getControlForwarding().get(router, ge);
          assert (cForward != null);
          add(mkImplies(sends, cForward));

          // record the negation as well
          cfExprs.merge(ge, sends, (a, b) -> mkOr(a, b));
        }
      }

      // For edges that are never used, we constraint them to not be forwarded out of
      for (GraphEdge ge : getGraph().getEdgeMap().get(router)) {
        if (!constrained.contains(ge)/* && !_bothIfaceEdges.contains(ge)*/) {
          BoolExpr cForward = _symbolicDecisions.getControlForwarding().get(router, ge);
          assert (cForward != null);
          add(mkNot(cForward));
        }
      }

      // Handle the case that the router has no protocol running
      if (!someEdge) {
        for (GraphEdge ge : getGraph().getEdgeMap().get(router)) {
          /*if (_bothIfaceEdges.contains(ge)) {
            continue;
          }*/
          BoolExpr cForward = _symbolicDecisions.getControlForwarding().get(router, ge);
          assert (cForward != null);
          add(mkNot(cForward));
        }
      } else {
        // If no best route, then no forwarding
        Map<Protocol, List<ArrayList<LogicalEdge>>> map =
            _logicalGraph.getLogicalEdges().get(router);

        Set<GraphEdge> seen = new HashSet<>();
        for (List<ArrayList<LogicalEdge>> eList : map.values()) {
          for (ArrayList<LogicalEdge> edges : eList) {
            for (LogicalEdge le : edges) {
              GraphEdge ge = le.getEdge();
              if (seen.contains(ge)) {
                continue;
              }
              seen.add(ge);
              BoolExpr expr = cfExprs.get(ge);
              BoolExpr cForward = _symbolicDecisions.getControlForwarding().get(router, ge);
              assert (cForward != null);
              if (expr != null) {
                add(mkImplies(mkNot(expr), mkNot(cForward)));
              } else {
                add(mkNot(cForward));
              }
            }
          }
        }
      }
    }
  }

  public boolean matchIpWithPref(IpWildcard addr, Prefix wc) {
    if (addr.containsIp(wc.getStartIp()) || wc.containsIp(addr.getIp())) {
      return true;
    }
    return false;
  }

  private boolean matchIp(IpWildcard addr, IpWildcard wc) {
    if (addr.containsIp(wc.getIp()) || wc.containsIp(addr.getIp())) {
      return true;
    }
    return false;
  }
  /*
  private boolean shouldAddACL(Set<IpWildcard> wcs, IpWildcard addr) {
    for (IpWildcard wc : wcs) {
      if(matchIp(addr, wc)) {
        return true;
      }
    }
    return false;
  }
  */

  /*
   * Convert a set of ranges and a packet field to a symbolic boolean expression
   */
  private BoolExpr computeValidRange(Set<SubRange> ranges, ArithExpr field) {
    BoolExpr acc = mkFalse();
    for (SubRange range : ranges) {
      int start = range.getStart();
      int end = range.getEnd();
      if (start == end) {
        BoolExpr val = mkEq(field, mkInt(start));
        acc = mkOr(acc, val);
      } else {
        BoolExpr val1 = mkGe(field, mkInt(start));
        BoolExpr val2 = mkLe(field, mkInt(end));
        acc = mkOr(acc, mkAnd(val1, val2));
      }
    }
    return (BoolExpr) acc.simplify();
  }

  /*
   * Convert a Tcp flag to a boolean expression on the symbolic packet
   */
  private BoolExpr computeTcpFlags(TcpFlags flags) {
    BoolExpr acc = mkTrue();
    if (flags.getUseAck()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpAck(), mkBool(flags.getAck())));
    }
    if (flags.getUseCwr()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpCwr(), mkBool(flags.getCwr())));
    }
    if (flags.getUseEce()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpEce(), mkBool(flags.getEce())));
    }
    if (flags.getUseFin()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpFin(), mkBool(flags.getFin())));
    }
    if (flags.getUsePsh()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpPsh(), mkBool(flags.getPsh())));
    }
    if (flags.getUseRst()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpRst(), mkBool(flags.getRst())));
    }
    if (flags.getUseSyn()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpSyn(), mkBool(flags.getSyn())));
    }
    if (flags.getUseUrg()) {
      acc = mkAnd(acc, mkEq(_symbolicPacket.getTcpUrg(), mkBool(flags.getUrg())));
    }
    return (BoolExpr) acc.simplify();
  }

  /*
   * Convert Tcp flags to a boolean expression on the symbolic packet
   */
  private BoolExpr computeTcpFlags(List<TcpFlags> flags) {
    BoolExpr acc = mkFalse();
    for (TcpFlags fs : flags) {
      acc = mkOr(acc, computeTcpFlags(fs));
    }
    return (BoolExpr) acc.simplify();
  }

  /*
   * Convert a set of ip protocols to a boolean expression on the symbolic packet
   */
  private BoolExpr computeIpProtocols(Set<IpProtocol> ipProtos) {
    BoolExpr acc = mkFalse();
    for (IpProtocol proto : ipProtos) {
      ArithExpr protoNum = mkInt(proto.number());
      acc = mkOr(acc, mkEq(protoNum, _symbolicPacket.getIpProtocol()));
    }
    return (BoolExpr) acc.simplify();
  }

  private boolean hasZero(Set<IpWildcard> wcs) {
    for (IpWildcard wc : wcs) {
      if(wc.toString().equals("0.0.0.0/0")) {
        return true;
      }
    }
    return false;
  }
  /*
  private boolean existsACL(IpAccessList acl) {
    if (acl == null) {
      return false;
    }
    List<IpAccessListLine> lines = new ArrayList<>(acl.getLines());
    Collections.reverse(lines);
    //bool exists = false;
    for (IpAccessListLine l : lines) {
      if (l.getDstIps() == null && l.getSrcIps() == null) {
        continue;
      }
      if (l.getSrcIps() == null) { 
        if (hasZero(l.getDstIps())) {
          continue;
        }
      } else if (l.getDstIps() == null) { 
        if (hasZero(l.getSrcIps())) {
          continue;
        }
      } else {
        if (hasZero(l.getSrcIps()) && hasZero(l.getDstIps())) {
          continue;
        }
      }
      boolean individualExists = true;
      if (l.getDstIps() != null && _encoder._dstIp != null) {
        individualExists = individualExists & shouldAddACL(l.getDstIps(), _encoder._dstIp);
      }
      if (l.getSrcIps() != null && _encoder._srcIp != null) {
        individualExists = individualExists & shouldAddACL(l.getSrcIps(), _encoder._srcIp);
      }
      if (individualExists == true) {
        return true;
      }
    }
    return false;
  }
  */
  /*
   * Convert an Access Control List (ACL) to a symbolic boolean expression.
   * The default action in an ACL is to deny all traffic.
   */
  private BoolExpr computeACL(IpAccessList acl) {

    // Check if there is an ACL first
    if (acl == null) {
      return mkTrue();
    }

    return new IpAccessListToBoolExpr(_encoder.getCtx(), _symbolicPacket).toBoolExpr(acl, _encoder);
  }

  private boolean otherSliceHasEdge(EncoderSlice slice, String r, GraphEdge ge) {
    Map<String, List<GraphEdge>> edgeMap = slice.getGraph().getEdgeMap();
    List<GraphEdge> edges = edgeMap.get(r);
    return edges != null && edges.contains(ge);
  }

  /*
   * Constraints for the final data plane forwarding behavior.
   * Forwarding occurs in the data plane if the control plane decides
   * to use an interface, and no ACL blocks the packet:
   *
   * data_fwd(iface) = control_fwd(iface) and not acl(iface)
   */
  private void addDataForwardingConstraints() {
    for (Entry<String, List<GraphEdge>> entry : getGraph().getEdgeMap().entrySet()) {
      String router = entry.getKey();
      int statNum = 0;
      List<GraphEdge> edges = entry.getValue();
      for (GraphEdge ge : edges) {

        // setup forwarding for non-abstract edges
        if (!ge.isAbstract()) {
          BoolExpr fwd = mkFalse();
          BoolExpr cForward = _symbolicDecisions.getControlForwarding().get(router, ge);
          BoolExpr dForward = _symbolicDecisions.getDataForwarding().get(router, ge);
          assert (cForward != null);
          assert (dForward != null);

          // for each abstract control edge,
          // if that edge is on and its neighbor slices has next hop forwarding
          // out the current edge ge, the we use ge.
          for (GraphEdge ge2 : getGraph().getEdgeMap().get(router)) {
            if (ge2.isAbstract()) {
              BoolExpr ctrlFwd = getSymbolicDecisions().getControlForwarding().get(router, ge2);
              Graph.BgpSendType st = getGraph().peerType(ge2);
              // If Route reflectors, then next hop based on ID
              if (st == Graph.BgpSendType.TO_RR) {
                SymbolicRoute record = getSymbolicDecisions().getBestNeighbor().get(router);
                // adjust for iBGP in main slice
                BoolExpr acc = mkFalse();
                if (isMainSlice()) {
                  for (Entry<String, Integer> entry2 : getGraph().getOriginatorId().entrySet()) {
                    String r = entry2.getKey();
                    Integer id = entry2.getValue();
                    EncoderSlice s = _encoder.getSlice(r);
                    // Make sure
                    if (otherSliceHasEdge(s, router, ge)) {
                      BoolExpr outEdge =
                          s.getSymbolicDecisions().getDataForwarding().get(router, ge);
                      acc = mkOr(acc, mkAnd(record.getClientId().checkIfValue(id), outEdge));
                    }
                  }
                }
                fwd = mkOr(fwd, mkAnd(ctrlFwd, acc));

              } else {
                // Otherwise, we know the next hop statically
                // adjust for iBGP in main slice
                if (isMainSlice()) {
                  EncoderSlice s = _encoder.getSlice(ge2.getPeer());
                  if (otherSliceHasEdge(s, router, ge)) {
                    BoolExpr outEdge = s.getSymbolicDecisions().getDataForwarding().get(router, ge);
                    fwd = mkOr(fwd, mkAnd(ctrlFwd, outEdge));
                  }
                }
              }
            }
          }

          fwd = mkOr(fwd, cForward);
          BoolExpr acl = _outboundAcls.get(ge);
          if (acl == null) {
            acl = mkTrue();
          }
          BoolExpr notBlocked = mkAnd(fwd, acl);
          //* ARCHIE REMOVE
          if (ge.getStart()!=null && ge.getEnd()!=null && (!_bothIfaceEdges.contains(ge))) {
            BoolExpr shouldAdd;

            boolean noChange = router.endsWith("a") || router.endsWith("b");
            if (!noChange) {
              if (!_isTCE)
              {
                shouldAdd = getCtx().mkBoolConst(_encoder.getId() + "_" + router +
                 "-StaticRouteAddSoft-" + ge);
                if (_encoder._repairObjective == 0) {
                  addSoft(mkNot(shouldAdd), staticWeight, "StaticAdd");
                }
                _staticRouteAddSoft.put(ge, shouldAdd);
                _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), mkNot(shouldAdd)));
              } else {
                shouldAdd = _existsES.getStaticRouteAddSoft().get(ge);
              }
              Map<String, BoolExpr> add = new HashMap<>();
              add.put("add", shouldAdd);
              _Tree.get(router).get("process").get("origination").put(
                "static"+Integer.toString(statNum), add);
              statNum += 1;

              notBlocked = mkOr(notBlocked, shouldAdd);
            }
            
          }
          add(mkEq(mkAnd(notBlocked, mkEq(getSymbolicFailures().getFailedVariable(ge), mkInt(0))), dForward));
          //*/
          // ARCHIE ADD BACK add(mkEq(notBlocked, dForward));
        }
      }
    }
  }

  /*
   * Creates the transfer function to represent import filters
   * between two symbolic records. The import filter depends
   * heavily on the protocol.
   */
  private void addImportConstraint(
      LogicalEdge e,
      SymbolicRoute varsOther,
      Configuration conf,
      Protocol proto,
      GraphEdge ge,
      String router, 
      int ruleNum) {

    SymbolicRoute vars = e.getSymbolicRecord();

    Interface iface = ge.getStart();

    ArithExpr failed = getSymbolicFailures().getFailedVariable(e.getEdge());
    assert (failed != null);
    BoolExpr notFailed = mkEq(failed, mkInt(0));

    if (vars.getIsUsed()) {

      if (proto.isConnected()) {
        Prefix p = iface.getAddress().getPrefix();
        BoolExpr relevant =
            mkAnd(
                interfaceActive(iface, proto, e),
                isRelevantFor(p, _symbolicPacket.getDstIp()),
                notFailed);
        BoolExpr per = vars.getPermitted();
        BoolExpr len = safeEq(vars.getPrefixLength(), mkInt(p.getPrefixLength()));
        BoolExpr ad = safeEq(vars.getAdminDist(), mkInt(1));
        BoolExpr lp = safeEq(vars.getLocalPref(), mkInt(0));
        BoolExpr met = safeEq(vars.getMetric(), mkInt(0));
        BoolExpr values = mkAnd(per, len, ad, lp, met);
        add(mkIf(relevant, values, mkNot(vars.getPermitted())));
      }

      if (proto.isStatic()) {
        //System.out.println("Static Import " + e.getEdge());
        List<StaticRoute> srs = getGraph().getStaticRoutes().get(router, iface.getName());
        assert (srs != null);
        BoolExpr acc = mkNot(vars.getPermitted());
        for (StaticRoute sr : srs) {
          Prefix p = sr.getNetwork();
          BoolExpr relevant;
          //System.out.println("Static " + p);
          /*
          if (getEncoder()._dstIp != null && matchIpWithPref(getEncoder()._dstIp, p)) {
            System.out.println("Only req Stat Remove " + p);
            
              BoolExpr shouldRemove = getCtx().mkBoolConst(_encoder.getId() + "_"
               + router + "StaticRouteRemoveSoft" + p);
              addSoft(shouldRemove, staticWeight, "StaticRemove");
              _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), shouldRemove));  
              BoolExpr relevant =
                  mkAnd(
                      interfaceActive(iface, proto, e),
                      isRelevantFor(p, _symbolicPacket.getDstIp()),
                      notFailed,
                      shouldRemove);
                        
          }*/
          //* ARCHIE REMOVE
          boolean noChange = router.endsWith("a") || router.endsWith("b");
          if (noChange) {
            relevant =
                          mkAnd(
                              interfaceActive(iface, proto, e),
                              isRelevantFor(p, _symbolicPacket.getDstIp()),
                              notFailed);            
                        } else {
          BoolExpr shouldRemove = getCtx().mkBoolConst(_encoder.getId() + "_"
           + router + "StaticRouteRemoveSoft" + p);
          if (_encoder._repairObjective == 0) {
            addSoft(shouldRemove, staticWeight, "StaticRemove");
          }
          Map<String, BoolExpr> remove = new HashMap<>();
          remove.put("remove", shouldRemove);
          _Tree.get(router).get("process").get("origination").put(
            "static"+Integer.toString(ruleNum), remove);
          _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), shouldRemove));
          relevant =
              mkAnd(
                  interfaceActive(iface, proto, e),
                  isRelevantFor(p, _symbolicPacket.getDstIp()),
                  notFailed,
                  shouldRemove);
          }
          //*/
          /* ARCHIE ADD THIS BACK
          relevant =
              mkAnd(
                  interfaceActive(iface, proto, e),
                  isRelevantFor(p, _symbolicPacket.getDstIp()),
                  notFailed);
          //*/
          BoolExpr per = vars.getPermitted();
          BoolExpr len = safeEq(vars.getPrefixLength(), mkInt(p.getPrefixLength()));
          BoolExpr ad = safeEq(vars.getAdminDist(), mkInt(sr.getAdministrativeCost()));
          BoolExpr lp = safeEq(vars.getLocalPref(), mkInt(0));
          BoolExpr met = safeEq(vars.getMetric(), mkInt(0));
          BoolExpr values = mkAnd(per, len, ad, lp, met);
          acc = mkIf(relevant, values, acc);
        }
        add(acc);
      }

      if (proto.isOspf() || proto.isBgp()) {
        BoolExpr val = mkNot(vars.getPermitted());

        if (varsOther != null) {

          // BoolExpr isRoot = relevantOrigination(originations);
          BoolExpr active = interfaceActive(iface, proto, e);

          boolean isNonClient = false;
          boolean isClient = false;   
          if (!_bgpAllow.contains(e)) {
            // Handle iBGP by checking reachability to the next hop to send messages
            isNonClient =
                (proto.isBgp()) && (getGraph().peerType(ge) != Graph.BgpSendType.TO_EBGP);
            isClient =
                (proto.isBgp()) && (getGraph().peerType(ge) == Graph.BgpSendType.TO_RR);
          }

          BoolExpr receiveMessage;
          String currentRouter = ge.getRouter();
          String peerRouter = ge.getPeer();

          if (_encoder.getModelIgp() && isNonClient) {
            // Lookup reachabilty based on peer next-hop
            receiveMessage = _encoder.getSliceReachability().get(currentRouter).get(peerRouter);
            /* EncoderSlice peerSlice = _encoder.getSlice(peerRouter);
            BoolExpr srcPort = mkEq(peerSlice.getSymbolicPacket().getSrcPort(), mkInt(179));
            BoolExpr srcIp = mkEq(peerSlice.getSymbolicPacket().getSrcIp(), mkInt(0));
            BoolExpr tcpAck = mkEq(peerSlice.getSymbolicPacket().getTcpAck(), mkFalse());
            BoolExpr tcpCwr = mkEq(peerSlice.getSymbolicPacket().getTcpCwr(), mkFalse());
            BoolExpr tcpEce = mkEq(peerSlice.getSymbolicPacket().getTcpEce(), mkFalse());
            BoolExpr tcpFin = mkEq(peerSlice.getSymbolicPacket().getTcpFin(), mkFalse());
            BoolExpr tcpPsh = mkEq(peerSlice.getSymbolicPacket().getTcpPsh(), mkFalse());
            BoolExpr tcpRst = mkEq(peerSlice.getSymbolicPacket().getTcpRst(), mkFalse());
            BoolExpr tcpSyn = mkEq(peerSlice.getSymbolicPacket().getTcpSyn(), mkFalse());
            BoolExpr tcpUrg = mkEq(peerSlice.getSymbolicPacket().getTcpUrg(), mkFalse());
            BoolExpr icmpCode = mkEq(peerSlice.getSymbolicPacket().getIcmpCode(), mkInt(0));
            BoolExpr icmpType = mkEq(peerSlice.getSymbolicPacket().getIcmpType(), mkInt(0));
            BoolExpr all =
                mkAnd(srcPort, srcIp, tcpAck,
                    tcpCwr, tcpEce, tcpFin, tcpPsh, tcpRst, tcpSyn, tcpUrg, icmpCode, icmpType);
            receiveMessage = mkImplies(all, receiveMessage); */
          } else if (_encoder.getModelIgp() && isClient) {
            // Lookup reachability based on client id tag to find next hop
            BoolExpr acc = mkTrue();
            for (Map.Entry<String, Integer> entry : getGraph().getOriginatorId().entrySet()) {
              String r = entry.getKey();
              Integer id = entry.getValue();
              if (!r.equals(currentRouter)) {
                BoolExpr reach = _encoder.getSliceReachability().get(currentRouter).get(r);
                /* EncoderSlice peerSlice = _encoder.getSlice(r);
                BoolExpr srcPort = mkEq(peerSlice.getSymbolicPacket().getSrcPort(), mkInt(179));
                BoolExpr srcIp = mkEq(peerSlice.getSymbolicPacket().getSrcIp(), mkInt(0));
                BoolExpr tcpAck = mkEq(peerSlice.getSymbolicPacket().getTcpAck(), mkFalse());
                BoolExpr tcpCwr = mkEq(peerSlice.getSymbolicPacket().getTcpCwr(), mkFalse());
                BoolExpr tcpEce = mkEq(peerSlice.getSymbolicPacket().getTcpEce(), mkFalse());
                BoolExpr tcpFin = mkEq(peerSlice.getSymbolicPacket().getTcpFin(), mkFalse());
                BoolExpr tcpPsh = mkEq(peerSlice.getSymbolicPacket().getTcpPsh(), mkFalse());
                BoolExpr tcpRst = mkEq(peerSlice.getSymbolicPacket().getTcpRst(), mkFalse());
                BoolExpr tcpSyn = mkEq(peerSlice.getSymbolicPacket().getTcpSyn(), mkFalse());
                BoolExpr tcpUrg = mkEq(peerSlice.getSymbolicPacket().getTcpUrg(), mkFalse());
                BoolExpr icmpCode = mkEq(peerSlice.getSymbolicPacket().getIcmpCode(), mkInt(0));
                BoolExpr icmpType = mkEq(peerSlice.getSymbolicPacket().getIcmpType(), mkInt(0));
                BoolExpr all =
                    mkAnd(srcPort, srcIp, tcpAck,
                        tcpCwr, tcpEce, tcpFin, tcpPsh, tcpRst, tcpSyn, tcpUrg, icmpCode, icmpType);
                reach = mkImplies(all, reach); */
                acc = mkAnd(acc, mkImplies(varsOther.getClientId().checkIfValue(id), reach));
              }
            }
            receiveMessage = acc;

            // Just check if the link is failed
          } else {
            receiveMessage = notFailed;
          }

          // Take into account BGP loop prevention
          // The path length will prevent any isolated loops
          BoolExpr loop = mkFalse();
          if (proto.isBgp() && ge.getPeer() != null) {
            String peer = ge.getPeer();
            GraphEdge gePeer = getGraph().getOtherEnd().get(ge);
            loop = getSymbolicDecisions().getControlForwarding().get(peer, gePeer);
          }
          assert (loop != null);

          BoolExpr usable = mkAnd(mkNot(loop), active, varsOther.getPermitted(), receiveMessage);
          //* ARCHIE REMOVE
          if (_bgpAllow.contains(e) || _ospfAllow.contains(e)) {

            BoolExpr shouldAllow;
            String nameOfLE = e.getSymbolicRecord().getName();
            String keyvalue = nameOfLE.substring(nameOfLE.indexOf(_sliceName) + _sliceName.length() + 2) ;
            //System.out.println("Key = " + keyvalue + "  for  " + nameOfLE);
            
            if (_enableRoute.containsKey(keyvalue)) {
              shouldAllow = _enableRoute.get(keyvalue);
            } else {
              shouldAllow = getCtx().mkBoolConst(_encoder.getId() + "_"
                + keyvalue + "AllowChoiceUseSoft");
              if (_encoder._repairObjective == 0) {
                addSoft(mkNot(shouldAllow), enableAdjWeight, "AllowRouteSoft");
              }
              _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), mkNot(shouldAllow)));  
              _enableRoute.put(keyvalue, shouldAllow);
            }
            Map<String, BoolExpr> add = new HashMap<>();
            add.put("add", shouldAllow);
            _Tree.get(router).get("process").get("adjacency").put(
              "bgp"+Integer.toString(ruleNum), add);
            ruleNum += 1;

            
            //BoolExpr shouldAllow = getCtx().mkBoolConst(_encoder.getId() + "_"
            //  + vars.getName() + "AllowChoiceUse");
            //addSoft(mkNot(shouldAllow), 1, "AllowRoute");

            usable = mkAnd(usable, shouldAllow);
          }//*/
          BoolExpr importFunction;
          RoutingPolicy pol = getGraph().findImportRoutingPolicy(router, proto, e.getEdge());

          if (Encoder.ENABLE_DEBUGGING && pol != null) {
            System.out.println("Import Policy: " + pol.getName());
          }

          List<Statement> statements;
          if (pol == null) {
            //System.out.println("\nEmpty Import Policy\n");
            Statements.StaticStatement s = new Statements.StaticStatement(Statements.ExitAccept);
            statements = Collections.singletonList(s);
          } else {
            statements = pol.getStatements();
          }

          // OSPF cost calculated based on incoming interface
          Integer cost = proto.isOspf() ? addedCost(proto, ge) : 0;
          TransferSSA f =
              new TransferSSA(this, conf, varsOther, vars, proto, statements, cost, ge, false);
          importFunction = f.compute();
          //System.out.println("** IMPORT **\n" + ge + "   " + importFunction + "\n**   **");
          //* ARCHIE REMOVE
          BoolExpr shouldAddFilter = getCtx().mkBoolConst(_encoder.getId() + "_" + router
           + "ImportFilterAddSoft" + vars.getName());

          Map<String, Map<String, BoolExpr>> ruleAdd = new HashMap<>();
          Map<String, BoolExpr> add = new HashMap<>();
          add.put("add", shouldAddFilter);
          ruleAdd.put("match", add);
          _Tree.get(router).get("rfilter").put(Integer.toString(ruleNum), ruleAdd);

          if (_encoder._repairObjective == 0) {
            addSoft(shouldAddFilter, importWeight, "ImportFilterAdd");
          }
          _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), shouldAddFilter));  
          //importFunction = mkOr(importFunction, shouldAddFilter);
          BoolExpr acc = mkIf(mkAnd(usable, shouldAddFilter), importFunction, val);
          //*/
          // ARCHIE Add next line back 
          //BoolExpr acc = mkIf(usable, importFunction, val);
          if (Encoder.ENABLE_DEBUGGING) {
            System.out.println("IMPORT FUNCTION: " + router + " " + varsOther.getName());
            System.out.println(importFunction.simplify());
            System.out.println("\n\n");
          }
          add(acc);

        } else {
          add(val);
        }
      }
    }
  }

  /*
   * Creates the transfer function to represent export filters
   * between two symbolic records. The import filter depends
   * heavily on the protocol.
   */
  private void addExportConstraint(
      LogicalEdge e,
      SymbolicRoute varsOther,
      @Nullable SymbolicRoute ospfRedistribVars,
      @Nullable SymbolicRoute overallBest,
      Configuration conf,
      Protocol proto,
      GraphEdge ge,
      String router,
      boolean usedExport,
      Set<Prefix> originations,
      Set<Prefix> allOriginations) {

    SymbolicRoute vars = e.getSymbolicRecord();

    Interface iface = ge.getStart();

    ArithExpr failed = getSymbolicFailures().getFailedVariable(e.getEdge());
    assert (failed != null);
    BoolExpr notFailed = mkEq(failed, mkInt(0));

    // only add constraints once when using a single copy of export variables
    if (!_optimizations.getSliceCanKeepSingleExportVar().get(router).get(proto) || !usedExport) {

      if (proto.isConnected()) {
        //System.out.println("Connected Export");
        BoolExpr val = mkNot(vars.getPermitted());
        add(val);
      }

      if (proto.isStatic()) {
        //System.out.println("Static Export");
        BoolExpr val = mkNot(vars.getPermitted());
        add(val);
      }

      if (proto.isOspf() || proto.isBgp()) {

        // BGP cost based on export
        Integer cost = proto.isBgp() ? addedCost(proto, ge) : 0;

        BoolExpr val = mkNot(vars.getPermitted());
        BoolExpr active = interfaceActive(iface, proto, e);

        // Apply BGP export policy and cost based on peer type
        // (1) EBGP --> ALL
        // (2) CLIENT --> ALL
        // (3) NONCLIENT --> EBGP, CLIENT
        boolean isNonClientEdge = false;
        boolean isClientEdge = false;   
        if (!_bgpAllow.contains(e)) {
          isNonClientEdge =
              proto.isBgp() && getGraph().peerType(ge) != Graph.BgpSendType.TO_EBGP;
          isClientEdge =
              proto.isBgp() && getGraph().peerType(ge) == Graph.BgpSendType.TO_CLIENT;
        }
        boolean isInternalExport =
            varsOther.isBest() && _optimizations.getNeedBgpInternal().contains(router);
        BoolExpr doExport = mkTrue();
        if (isInternalExport && proto.isBgp() && isNonClientEdge) {
          if (isClientEdge) {
            cost = 0;
          } else {
            // Lookup if we learned from iBGP, and if so, don't export the route
            SymbolicRoute other = getBestNeighborPerProtocol(router, proto);
            assert other != null;
            assert other.getBgpInternal() != null;
            if (other.getBgpInternal() != null) {
              doExport = mkNot(other.getBgpInternal());
              cost = 0;
            }
          }
        }

        BoolExpr acc; // this is export constraints with originations prefixes
        BoolExpr allacc; // this is export constraints with allOriginations prefixes
        // @archie allacc is soft constraint which represents new prefixes being exported
        RoutingPolicy pol = getGraph().findExportRoutingPolicy(router, proto, e.getEdge());

        if (Encoder.ENABLE_DEBUGGING && pol != null) {
          System.out.println("Export policy (" + _sliceName + "," + ge + "): " + pol.getName());
        }

        // We have to wrap this with the right thing for some reason
        List<Statement> statements;
        if (proto.isOspf()) {
          If i =
              new If(
                  new MatchProtocol(RoutingProtocol.OSPF),
                  ImmutableList.of(Statements.ExitAccept.toStaticStatement()),
                  pol != null
                      ? pol.getStatements()
                      : ImmutableList.of(new Statements.StaticStatement(Statements.ExitReject)));
          statements = Collections.singletonList(i);
        } else {
          statements =
              (pol == null
                  ? Collections.singletonList(new Statements.StaticStatement(Statements.ExitAccept))
                  : pol.getStatements());
        }

        TransferSSA f =
            new TransferSSA(this, conf, varsOther, vars, proto, statements, cost, ge, true);
        acc = f.compute();
        //System.out.println("** EXPORT **\n" + acc + "\n**   **");
        BoolExpr usable = mkAnd(active, doExport, varsOther.getPermitted(), notFailed);
        /*
        SymbolicRoute softospf = null;
        if (proto.isOspf()) {
          softospf = _softOspfRedistributed.get(router);
        }*/
        // OSPF is complicated because it can have routes redistributed into it
        // from the FIB, but also needs to know about other routes in OSPF as well.
        // We model the export here as being the better of the redistributed route
        // and the OSPF exported route. This should work since every other router
        // will maintain the same preference when adding to the cost.
        if (ospfRedistribVars != null) {
          assert overallBest != null;
          f =
              new TransferSSA(
                  this, conf, overallBest, ospfRedistribVars, proto, statements, cost, ge, true);
          //* ARCHIE REMOVE
          BoolExpr redisRemove;
          // remove the sharing of redis concept
          String keyvalue = router + proto.name();
          if (_disableRedis.containsKey(keyvalue)) {
            redisRemove = _disableRedis.get(keyvalue);
          } else {
            redisRemove = getCtx().mkBoolConst(_encoder.getId() + "_"
             + router + proto.name() + "SoftRedisRemove");
            if (_encoder._repairObjective == 0) {
              addSoft(redisRemove, redisWeight, "RedisRemove");
            }
            Map<String, BoolExpr> remove = new HashMap<>();
            remove.put("remove", redisRemove);
            _Tree.get(router).get("process").get("adjacency").put(
              "redis"+Integer.toString(exportRuleNum), remove);
            exportRuleNum = exportRuleNum + 1;

            _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), redisRemove));  
            _disableRedis.put(keyvalue, redisRemove);
          }
          /*/
          
          BoolExpr redisRemove = getCtx().mkBoolConst(_encoder.getId() + "_"
           + router + proto.name() + "SoftRedisRemove");
          addSoft(redisRemove, 2, "RedisRemove");
          */
          BoolExpr acc2 = f.compute();
          //System.out.println("################\n" + acc2 + "\n#############");
          // System.out.println("ADDING: \n" + acc2.simplify());
          add(acc2);
          BoolExpr usable2 = mkAnd(active, doExport, ospfRedistribVars.getPermitted(), notFailed, redisRemove);
          //* ARCHIE REMOVE , redisRemove);
          // ARCHIE add back 
          //BoolExpr usable2 = mkAnd(active, doExport, ospfRedistribVars.getPermitted(), notFailed);
          BoolExpr geq = greaterOrEqual(conf, proto, ospfRedistribVars, varsOther, e);
          BoolExpr isBetter = mkNot(mkAnd(ospfRedistribVars.getPermitted(), geq));
          BoolExpr usesOspf = mkAnd(varsOther.getPermitted(), isBetter);
          BoolExpr eq = equal(conf, proto, ospfRedistribVars, vars, e, false);
          BoolExpr eqPer = mkEq(ospfRedistribVars.getPermitted(), vars.getPermitted());

          //System.out.println("**************\n" + mkIf(usesOspf, mkIf(usable, acc, val),
          // mkIf(usable2, mkAnd(eq, eqPer), val)) + "\n**********");

          acc = mkIf(usesOspf, mkIf(usable, acc, val), mkIf(usable2, mkAnd(eq, eqPer), val));
        } else {
          /*
          if (softospf!=null) {
            f =
                new TransferSSA(
                    this, conf, overallBest, softospf, proto, statements, cost, ge, true);

            BoolExpr acc2 = f.compute();
            // System.out.println("ADDING: \n" + acc2.simplify());
            //add(acc2);
            BoolExpr shouldAdd = getCtx().mkBoolConst(_encoder.getId() + "_" 
              + router + "RedisAddSoft" + proto.name());
            addSoft(mkNot(shouldAdd), 1, "RedisAdd");
            //add(mkEq(mkNot(shouldAdd), softospf.getPermitted()));

            BoolExpr usable2 = mkAnd(active, doExport, softospf.getPermitted(), notFailed, shouldAdd);
            //BoolExpr geq = greaterOrEqual(conf, proto, softospf, varsOther, e);
            //BoolExpr isBetter = mkNot(mkAnd(softospf.getPermitted(), geq));
            //BoolExpr usesOspf = mkAnd(varsOther.getPermitted(), isBetter);
            BoolExpr eq = equal(conf, proto, softospf, vars, e, false);
            BoolExpr eqPer = mkEq(softospf.getPermitted(), vars.getPermitted());

            //System.out.println("*!!!!!!!!!!!!!!!\n" + mkIf(usesOspf, mkIf(usable, acc, val),
            // mkIf(usable2, mkAnd(eq, eqPer), val)) + "\n*!!!!!!!!!!!!!!!");

            acc = mkIf(varsOther.getPermitted(), mkIf(usable, acc, val),
              mkIf(usable2, mkAnd(eq, eqPer), val));

          } else {
            acc = mkIf(usable, acc, val);
          }//*/
          acc = mkIf(usable, acc, val);
        }

        allacc = acc;
        // from now allacc will be different from acc as they deal with different prefixes
        //System.out.println("Originations " + originations + " All " + allOriginations);
        for (Prefix p : originations) {
          // For OSPF, we need to explicitly initiate a route
          /*
          if (getEncoder()._dstIp != null && !matchIpWithPref(getEncoder()._dstIp, p)) {
            //System.out.println("Only req ospf remove " + p);
            continue;
          }*/
          if (proto.isOspf()) {

            BoolExpr ifaceUp = interfaceActive(iface, proto, e);
            BoolExpr relevantPrefix = isRelevantFor(p, _symbolicPacket.getDstIp());
            //* ARCHIE REMOVE
            if (getEncoder()._dstIp != null && matchIpWithPref(getEncoder()._dstIp, p)) {
              BoolExpr shouldRemove;
              String keyvalue = router + p;
              if (_disableOSPF.containsKey(keyvalue)) {
                shouldRemove = _disableOSPF.get(keyvalue);
              } else {
                shouldRemove = getCtx().mkBoolConst(_encoder.getId() + "_"
                  + router + "OSPFExportRemoveSoft" + p);
                if (_encoder._repairObjective == 0) {
                  addSoft(shouldRemove, ospfWeight, "OSPFExportRemove"); 
                }

                _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), shouldRemove));  
                _disableOSPF.put(keyvalue, shouldRemove);
              }

              Map<String, BoolExpr> remove = new HashMap<>();
              remove.put("remove", shouldRemove);
              _Tree.get(router).get("process").get("origination").put(
                "ospf"+Integer.toString(exportRuleNum), remove);
              exportRuleNum = exportRuleNum + 1;

              //BoolExpr shouldRemove = getCtx().mkBoolConst(_encoder.getId() + "_"
              // + router + "OSPFExportRemoveSoft" + p);
              //addSoft(shouldRemove, 3, "OSPFExportRemove");
              relevantPrefix = mkAnd(relevantPrefix, shouldRemove);
            }//*/
            BoolExpr relevant = mkAnd(ifaceUp, relevantPrefix);

            int adminDistance = defaultAdminDistance(conf, proto);
            int prefixLength = p.getPrefixLength();

            BoolExpr per = vars.getPermitted();
            BoolExpr lp = safeEq(vars.getLocalPref(), mkInt(0));
            BoolExpr ad = safeEq(vars.getAdminDist(), mkInt(adminDistance));
            BoolExpr met = safeEq(vars.getMetric(), mkInt(cost));
            BoolExpr med = safeEq(vars.getMed(), mkInt(100));
            BoolExpr len = safeEq(vars.getPrefixLength(), mkInt(prefixLength));
            BoolExpr type = safeEqEnum(vars.getOspfType(), OspfType.O);
            BoolExpr area = safeEqEnum(vars.getOspfArea(), iface.getOspfAreaName());
            BoolExpr internal = safeEq(vars.getBgpInternal(), mkFalse());
            BoolExpr igpMet = safeEq(vars.getIgpMetric(), mkInt(0));
            BoolExpr comms = mkTrue();
            for (Map.Entry<CommunityVar, BoolExpr> entry : vars.getCommunities().entrySet()) {
              comms = mkAnd(comms, mkNot(entry.getValue()));
            }
            BoolExpr values =
                mkAnd(per, lp, ad, met, med, len, type, area, internal, igpMet, comms);

            // Don't originate OSPF route when there is a better redistributed route
            if (ospfRedistribVars != null) {
              BoolExpr betterLen = mkGt(ospfRedistribVars.getPrefixLength(), mkInt(prefixLength));
              BoolExpr equalLen = mkEq(ospfRedistribVars.getPrefixLength(), mkInt(prefixLength));
              BoolExpr betterAd = mkLt(ospfRedistribVars.getAdminDist(), mkInt(110));
              BoolExpr better = mkOr(betterLen, mkAnd(equalLen, betterAd));
              BoolExpr betterRedistributed = mkAnd(ospfRedistribVars.getPermitted(), better);
              relevant = mkAnd(relevant, mkNot(betterRedistributed));
            }/* else if (softospf != null) {
              BoolExpr betterRedistributed = softospf.getPermitted();
              relevant = mkAnd(relevant, mkNot(betterRedistributed));              
            }*/

            acc = mkIf(relevant, values, acc);
          }
        }
        //* ARCHIE REMOVE
        boolean noChange = router.endsWith("a") || router.endsWith("b");
        if (!noChange) {
          for (Prefix p : allOriginations) {
            // For OSPF, we need to explicitly initiate a route
            if (proto.isOspf()) {

              BoolExpr ifaceUp = interfaceActive(iface, proto, e);
              BoolExpr relevantPrefix = isRelevantFor(p, _symbolicPacket.getDstIp());
              BoolExpr shouldAdd = getCtx().mkBoolConst(_encoder.getId() + "_"
               + router + "OSPFExportAddSoft" + p);
              if (_encoder._repairObjective == 0) {
                addSoft(mkNot(shouldAdd), ospfWeight, "OSPFExportAdd");
              }

              Map<String, BoolExpr> add = new HashMap<>();
              add.put("add", shouldAdd);
              _Tree.get(router).get("process").get("origination").put(
                "ospf"+Integer.toString(exportRuleNum), add);
              exportRuleNum = exportRuleNum + 1;

              _routerConsMap.put(router, mkAnd(_routerConsMap.get(router), mkNot(shouldAdd)));  
              relevantPrefix = mkAnd(relevantPrefix, shouldAdd);
              BoolExpr relevant = mkAnd(ifaceUp, relevantPrefix);

              int adminDistance = defaultAdminDistance(conf, proto);
              int prefixLength = p.getPrefixLength();

              BoolExpr per = vars.getPermitted();
              BoolExpr lp = safeEq(vars.getLocalPref(), mkInt(0));
              BoolExpr ad = safeEq(vars.getAdminDist(), mkInt(adminDistance));
              BoolExpr met = safeEq(vars.getMetric(), mkInt(cost));
              BoolExpr med = safeEq(vars.getMed(), mkInt(100));
              BoolExpr len = safeEq(vars.getPrefixLength(), mkInt(prefixLength));
              BoolExpr type = safeEqEnum(vars.getOspfType(), OspfType.O);
              BoolExpr area = safeEqEnum(vars.getOspfArea(), iface.getOspfAreaName());
              BoolExpr internal = safeEq(vars.getBgpInternal(), mkFalse());
              BoolExpr igpMet = safeEq(vars.getIgpMetric(), mkInt(0));
              BoolExpr comms = mkTrue();
              for (Map.Entry<CommunityVar, BoolExpr> entry : vars.getCommunities().entrySet()) {
                comms = mkAnd(comms, mkNot(entry.getValue()));
              }
              BoolExpr values =
                  mkAnd(per, lp, ad, met, med, len, type, area, internal, igpMet, comms);

              // Don't originate OSPF route when there is a better redistributed route
              if (ospfRedistribVars != null) {
                BoolExpr betterLen = mkGt(ospfRedistribVars.getPrefixLength(), mkInt(prefixLength));
                BoolExpr equalLen = mkEq(ospfRedistribVars.getPrefixLength(), mkInt(prefixLength));
                BoolExpr betterAd = mkLt(ospfRedistribVars.getAdminDist(), mkInt(110));
                BoolExpr better = mkOr(betterLen, mkAnd(equalLen, betterAd));
                BoolExpr betterRedistributed = mkAnd(ospfRedistribVars.getPermitted(), better);
                relevant = mkAnd(relevant, mkNot(betterRedistributed));
              }// else if (softospf != null) {
              //  BoolExpr betterRedistributed = softospf.getPermitted();
               // relevant = mkAnd(relevant, mkNot(betterRedistributed));              
              //}

              acc = mkIf(relevant, values, acc);
            }
          }
        }//*/

        /*
        if (proto.isOspf()) {
          BoolExpr shouldAdd = getCtx().mkBoolConst(_encoder.getId() + "_"
           + router + "OSPFExportAddSoft");
          addSoft(mkNot(shouldAdd), 3, "OSPFExportAdd");
          acc = (mkOr(shouldAdd, acc));
        }
        *///*/
        //System.out.println("Exp: \n" + acc);
        /*
        // @archie duplicated but with allOriginations prefix set
        for (Prefix p : allOriginations) {
          // For OSPF, we need to explicitly initiate a route
          if (proto.isOspf()) {

            BoolExpr ifaceUp = interfaceActive(iface, proto);
            BoolExpr relevantPrefix = isRelevantFor(p, _symbolicPacket.getDstIp());
            BoolExpr relevant = mkAnd(ifaceUp, relevantPrefix);

            int adminDistance = defaultAdminDistance(conf, proto);
            int prefixLength = p.getPrefixLength();

            BoolExpr per = vars.getPermitted();
            BoolExpr lp = safeEq(vars.getLocalPref(), mkInt(0));
            BoolExpr ad = safeEq(vars.getAdminDist(), mkInt(adminDistance));
            BoolExpr met = safeEq(vars.getMetric(), mkInt(cost));
            BoolExpr med = safeEq(vars.getMed(), mkInt(100));
            BoolExpr len = safeEq(vars.getPrefixLength(), mkInt(prefixLength));
            BoolExpr type = safeEqEnum(vars.getOspfType(), OspfType.O);
            BoolExpr area = safeEqEnum(vars.getOspfArea(), iface.getOspfAreaName());
            BoolExpr internal = safeEq(vars.getBgpInternal(), mkFalse());
            BoolExpr igpMet = safeEq(vars.getIgpMetric(), mkInt(0));
            BoolExpr comms = mkTrue();
            for (Map.Entry<CommunityVar, BoolExpr> entry : vars.getCommunities().entrySet()) {
              comms = mkAnd(comms, mkNot(entry.getValue()));
            }
            BoolExpr values =
                mkAnd(per, lp, ad, met, med, len, type, area, internal, igpMet, comms);

            // Don't originate OSPF route when there is a better redistributed route
            if (ospfRedistribVars != null) {
              BoolExpr betterLen = mkGt(ospfRedistribVars.getPrefixLength(), mkInt(prefixLength));
              BoolExpr equalLen = mkEq(ospfRedistribVars.getPrefixLength(), mkInt(prefixLength));
              BoolExpr betterAd = mkLt(ospfRedistribVars.getAdminDist(), mkInt(110));
              BoolExpr better = mkOr(betterLen, mkAnd(equalLen, betterAd));
              BoolExpr betterRedistributed = mkAnd(ospfRedistribVars.getPermitted(), better);
              relevant = mkAnd(relevant, mkNot(betterRedistributed));
            }

            allacc = mkIf(relevant, values, allacc);
          }
        }

        add(mkOr(acc,allacc));
        addSoft(mkOr(acc, mkNot(allacc)), 2, "exportIPSoft");
        //*/
        add(acc);
        if (Encoder.ENABLE_DEBUGGING) {
          System.out.println("EXPORT: " + router + " " + varsOther.getName() + " " + ge);
          System.out.println(acc.simplify());
          System.out.println("\n\n");
        }

      }
    }
  }

  /*
   * Constraints that define relationships between various messages
   * in the network. The same transfer function abstraction is used
   * for both import and export constraints by relating different collections
   * of variables.
   */
  private void addTransferFunction() {
    for (Entry<String, Configuration> entry : getGraph().getConfigurations().entrySet()) {
      String router = entry.getKey();
      int ruleNum = 0;
      exportRuleNum = 0;
      Configuration conf = entry.getValue();
      for (Protocol proto : getProtocols().get(router)) {
        Boolean usedExport = false;
        boolean hasEdge = false;

        List<ArrayList<LogicalEdge>> les = _logicalGraph.getLogicalEdges().get(router, proto);
        assert (les != null);
        for (ArrayList<LogicalEdge> eList : les) {
          for (LogicalEdge e : eList) {
            GraphEdge ge = e.getEdge();

            if (!getGraph().isEdgeUsed(conf, proto, ge) && !(
              _ospfAllow.contains(e) ||  _bgpAllow.contains(e))) {
              continue;
            }

            hasEdge = true;
            SymbolicRoute varsOther;

            switch (e.getEdgeType()) {
              case IMPORT:
                varsOther = _logicalGraph.findOtherVars(e);
                addImportConstraint(e, varsOther, conf, proto, ge, router, ruleNum);
                ruleNum += 1;
                break;

              case EXPORT:
                // OSPF export is tricky because it does not depend on being
                // in the FIB. So it can come from either a redistributed route
                // or another OSPF route. We always take the direct OSPF
                SymbolicRoute ospfRedistribVars = null;
                SymbolicRoute overallBest = null;
                if (proto.isOspf()) {
                  varsOther = getBestNeighborPerProtocol(router, proto);
                  if (_ospfRedistributed.containsKey(router)) {
                    ospfRedistribVars = _ospfRedistributed.get(router);
                    overallBest = _symbolicDecisions.getBestNeighbor().get(router);
                  }/* else if(_softOspfRedistributed.containsKey(router)) {
                    overallBest = _symbolicDecisions.getBestNeighbor().get(router);
                  }*/
                } else {
                  varsOther = _symbolicDecisions.getBestNeighbor().get(router);
                }
                Set<Prefix> originations = _originatedNetworks.get(router, proto);
                Set<Prefix> allOriginations = _allOriginatedNetworks.get(router, proto);

                assert varsOther != null;
                assert originations != null;

                addExportConstraint(
                    e,
                    varsOther,
                    ospfRedistribVars,
                    overallBest,
                    conf,
                    proto,
                    ge,
                    router,
                    usedExport,
                    originations,
                    allOriginations);

                usedExport = true;
                break;

              default:
                break;
            }
          }
        }
        // If no edge used, then just set the best record to be false for that protocol
        if (!hasEdge) {
          SymbolicRoute protoBest;
          if (_optimizations.getSliceHasSingleProtocol().contains(router)) {
            protoBest = _symbolicDecisions.getBestNeighbor().get(router);
          } else {
            protoBest = _symbolicDecisions.getBestNeighborPerProtocol().get(router, proto);
          }
          assert protoBest != null;
          add(mkNot(protoBest.getPermitted()));
        }
      }
    }
  }

  /*
   * Constraints that ensure the protocol choosen by the best choice is accurate.
   * This is important because redistribution depends on the protocol used
   * in the actual FIB.
   */
  private void addHistoryConstraints() {
    for (Entry<String, SymbolicRoute> entry : _symbolicDecisions.getBestNeighbor().entrySet()) {
      String router = entry.getKey();
      SymbolicRoute vars = entry.getValue();
      if (_optimizations.getSliceHasSingleProtocol().contains(router)) {
        Protocol proto = getProtocols().get(router).get(0);
        add(mkImplies(vars.getPermitted(), vars.getProtocolHistory().checkIfValue(proto)));
      }
    }
  }

  /*
   * For performance reasons, we add constraints that if a message is not
   * valid, then the other variables will use default values. This speeds
   * up the solver significantly.
   */
  private void addUnusedDefaultValueConstraints() {
    for (SymbolicRoute vars : getAllSymbolicRecords()) {

      BoolExpr notPermitted = mkNot(vars.getPermitted());
      ArithExpr zero = mkInt(0);

      if (vars.getAdminDist() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getAdminDist(), zero)));
      }
      if (vars.getMed() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getMed(), zero)));
      }
      if (vars.getLocalPref() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getLocalPref(), zero)));
      }
      if (vars.getPrefixLength() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getPrefixLength(), zero)));
      }
      if (vars.getMetric() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getMetric(), zero)));
      }
      if (vars.getOspfArea() != null) {
        add(mkImplies(notPermitted, vars.getOspfArea().isDefaultValue()));
      }
      if (vars.getOspfType() != null) {
        add(mkImplies(notPermitted, vars.getOspfType().isDefaultValue()));
      }
      if (vars.getProtocolHistory() != null) {
        add(mkImplies(notPermitted, vars.getProtocolHistory().isDefaultValue()));
      }
      if (vars.getBgpInternal() != null) {
        add(mkImplies(notPermitted, mkNot(vars.getBgpInternal())));
      }
      if (vars.getClientId() != null) {
        add(mkImplies(notPermitted, vars.getClientId().isDefaultValue()));
      }
      if (vars.getIgpMetric() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getIgpMetric(), zero)));
      }
      if (vars.getRouterId() != null) {
        add(mkImplies(notPermitted, mkEq(vars.getRouterId(), zero)));
      }
      vars.getCommunities().forEach((cvar, e) -> add(mkImplies(notPermitted, mkNot(e))));
    }
  }


  /*
   * Add to a map
   */
  private void addtoMap() {

    Symbol temp;
    for (SymbolicRoute vars : getAllSymbolicRecords()) {
      String symName = vars.getName();
      _allBoolVars.add(vars.getPermitted());
      temp = getCtx().mkSymbol(symName + "_permitted");
      _allBoolVarsList.add(temp);

      if (vars.getAdminDist() != null) {
        _allArithVars.add(vars.getAdminDist());
        temp = getCtx().mkSymbol(symName + "_adminDist");
        _allArithVarsList.add(temp);        
      }
      if (vars.getMed() != null) {
        _allArithVars.add(vars.getMed());
        temp = getCtx().mkSymbol(symName + "_med");
        _allArithVarsList.add(temp);        
      }
      if (vars.getLocalPref() != null) {
        _allArithVars.add(vars.getLocalPref());
        temp = getCtx().mkSymbol(symName + "_localPref");
        _allArithVarsList.add(temp);        
      }
      if (vars.getPrefixLength() != null) {
        _allArithVars.add(vars.getPrefixLength());
        temp = getCtx().mkSymbol(symName + "_prefixLength");
        _allArithVarsList.add(temp);        
      }
      if (vars.getMetric() != null) {
        _allArithVars.add(vars.getMetric());
        temp = getCtx().mkSymbol(symName + "_metric");
        _allArithVarsList.add(temp);        
      }
      if (vars.getBgpInternal() != null) {
        _allBoolVars.add(vars.getBgpInternal());
        temp = getCtx().mkSymbol(symName + "_bgpInternal");
        _allBoolVarsList.add(temp);
      }
      if (vars.getIgpMetric() != null) {
        _allArithVars.add(vars.getIgpMetric());
        temp = getCtx().mkSymbol(symName + "_igpMetric");
        _allArithVarsList.add(temp);        
      }
      if (vars.getRouterId() != null) {
        _allArithVars.add(vars.getRouterId());
        temp = getCtx().mkSymbol(symName + "_routerID");
        _allArithVarsList.add(temp);        
      }
      if (vars.getRouterId() != null) {
        _allArithVars.add(vars.getRouterId());
        temp = getCtx().mkSymbol(symName + "_routerID");
        _allArithVarsList.add(temp);        
      }
      if (vars.getRouterId() != null) {
        _allArithVars.add(vars.getRouterId());
        temp = getCtx().mkSymbol(symName + "_routerID");
        _allArithVarsList.add(temp);        
      }
      if (vars.getOspfArea() != null) {
        _allBVVars.add(vars.getOspfArea()._bitvec);
        _allBVValues.put(_symbolicPacket.getDstIp(), vars.getOspfArea()._numBits);
        _allArithVars.add(vars.getRouterId());
        temp = getCtx().mkSymbol(symName + "_ospfArea");
        _allBVVarsList.add(temp);
        _allBVValuesMap.put(temp, vars.getOspfArea()._numBits);

      }
      if (vars.getOspfType() != null) {
        _allBVVars.add(vars.getOspfType()._bitvec);
        _allBVValues.put(_symbolicPacket.getDstIp(), vars.getOspfType()._numBits);
        _allArithVars.add(vars.getRouterId());
        temp = getCtx().mkSymbol(symName + "_ospfType");
        _allBVVarsList.add(temp); 
        _allBVValuesMap.put(temp, vars.getOspfType()._numBits);       
      }

    }

    getSymbolicFailures().getFailedInternalLinks().forEach((router, peer, var) -> _allArithVars.add(var));
    getSymbolicFailures().getFailedEdgeLinks().forEach((ge, var) -> _allArithVars.add(var));

    String packname = _encoder.getId() + "_" + _sliceName;
    _allArithVars.add(_symbolicPacket.getDstPort());
    temp = getCtx().mkSymbol(packname + "dst-port");
    _allArithVarsList.add(temp);        

    _allArithVars.add(_symbolicPacket.getSrcPort());
    temp = getCtx().mkSymbol(packname + "src-port");
    _allArithVarsList.add(temp);        

    _allArithVars.add(_symbolicPacket.getIcmpType());
    temp = getCtx().mkSymbol(packname + "icmp-type");
    _allArithVarsList.add(temp);        

    _allArithVars.add(_symbolicPacket.getIpProtocol());
    temp = getCtx().mkSymbol(packname + "ip-protocol");
    _allArithVarsList.add(temp);        

    _allArithVars.add(_symbolicPacket.getIcmpCode());
    temp = getCtx().mkSymbol(packname + "icmp-code");
    _allArithVarsList.add(temp);        

    if (_symbolicPacket.getDstIp() != null) {
      _allBVVars.add(_symbolicPacket.getDstIp());
      _allBVValues.put(_symbolicPacket.getDstIp(), 32);
      String dstname = packname + "dst-ip";
      temp = getCtx().mkSymbol(dstname);
      _allBVVarsList.add(temp);
      _allBVValuesMap.put(temp, 32);

    }

    if (_symbolicPacket.getSrcIp() != null) {
      _allBVVars.add(_symbolicPacket.getSrcIp());
      _allBVValues.put(_symbolicPacket.getSrcIp(), 32);

      String srcname = packname + "src-ip";
      temp = getCtx().mkSymbol(srcname);
      _allBVVarsList.add(temp);
      _allBVValuesMap.put(temp, 32);

    }

  }



  /*
   * Add constraints for the type of packets we will consider in the model.
   * This can include restrictions on any packet field such as dstIp, protocol etc.
   */
  private void addHeaderSpaceConstraint() {
    Context ctx = _encoder.getCtx();
    add(
        new IpAccessListToBoolExpr(ctx, _symbolicPacket)
            .visitMatchHeaderSpace(new MatchHeaderSpace(_headerSpace)));
  }

  /*
   * Add various constraints for well-formed environments
   */
  private void addEnvironmentConstraints() {
    for (SymbolicRoute vars : getLogicalGraph().getEnvironmentVars().values()) {
      // Environment messages are not internal
      if (vars.getBgpInternal() != null) {
        add(mkNot(vars.getBgpInternal()));
      }
      // Environment messages have 0 value for client id
      if (vars.getClientId() != null) {
        add(vars.getClientId().isNotFromClient());
      }
    }
  }

  /*
   * Compute the network encoding by adding all the
   * relevant constraints.
   */
  void computeEncoding() {
    if (_isTCE) {
      addSymbolicPacketBoundConstraints();
      addDataForwardingConstraints();
      addHeaderSpaceConstraint();
      addtoMap();
      return;
    }
    addtoMap();
    addBoundConstraints();
    addCommunityConstraints();
    addTransferFunction();
    addHistoryConstraints();
    addBestPerProtocolConstraints();
    addChoicePerProtocolConstraints();
    addBestOverallConstraints();
    addControlForwardingConstraints();
    addDataForwardingConstraints();
    addUnusedDefaultValueConstraints();
    addHeaderSpaceConstraint();
    if (isMainSlice()) {
      addEnvironmentConstraints();
    }
  }

  /*
   * Getters and Setters
   */

  Graph getGraph() {
    return _logicalGraph.getGraph();
  }

  Encoder getEncoder() {
    return _encoder;
  }

  Map<String, List<Protocol>> getProtocols() {
    return _optimizations.getProtocols();
  }

  String getSliceName() {
    return _sliceName;
  }

  Context getCtx() {
    return _encoder.getCtx();
  }

  Solver getSolver() {
    return _encoder.getSolver();
  }

  Optimize getOptimize() {
    return _encoder.getOptimize();
  }

  Map<String, Expr> getAllVariables() {
    return _encoder.getAllVariables();
  }

  Optimizations getOptimizations() {
    return _optimizations;
  }

  LogicalGraph getLogicalGraph() {
    return _logicalGraph;
  }

  SymbolicDecisions getSymbolicDecisions() {
    return _symbolicDecisions;
  }

  SymbolicPacket getSymbolicPacket() {
    return _symbolicPacket;
  }

  Map<GraphEdge, BoolExpr> getIncomingAcls() {
    return _inboundAcls;
  }

  Table2<String, GraphEdge, BoolExpr> getForwardsAcross() {
    return _forwardsAcross;
  }

  Set<CommunityVar> getAllCommunities() {
    return getGraph().getAllCommunities();
  }

  Map<String, String> getNamedCommunities() {
    return getGraph().getNamedCommunities();
  }

  UnsatCore getUnsatCore() {
    return _encoder.getUnsatCore();
  }

  private List<SymbolicRoute> getAllSymbolicRecords() {
    return _allSymbolicRoutes;
  }

  private SymbolicFailures getSymbolicFailures() {
    return _encoder.getSymbolicFailures();
  }

  Map<CommunityVar, List<CommunityVar>> getCommunityDependencies() {
    return getGraph().getCommunityDependencies();
  }

  Table2<String, Protocol, Set<Prefix>> getOriginatedNetworks() {
    return _originatedNetworks;
  }

  Table2<String, Protocol, Set<Prefix>> getAllOriginatedNetworks() {
    return _allOriginatedNetworks;
  }

   private Table2<String, Protocol, Set<Protocol>> getSoftRedistributed() {
    return _softRedistributed;
  }

  Set<GraphEdge> getBothIfaceEdges() {
    return _bothIfaceEdges;
  }

  Map<String, SymbolicRoute> getOspfRedistributed() {
    return _ospfRedistributed;
  }

  Map<String, SymbolicRoute> getSoftOspfRedistributed() {
    return _softOspfRedistributed;
  }

  Map<GraphEdge, BoolExpr> getStaticRouteAddSoft() {
    return _staticRouteAddSoft;
  }

  TreeSet<Integer> getLocalPrefSet() {
    return _localPref;
  }

  Map<String, ArithExpr> getLocalPrefMap() {
    return _localPrefMap;
  }

  Map<String, BoolExpr> getOspfDisableMap() {
    return _disableOSPF;
  }

  Map<String, BoolExpr> getRedisDisableMap() {
    return _disableRedis;
  }

  Map<String, BoolExpr> getEnableRouteMap() {
    return _enableRoute;
  }

  Set<BoolExpr> getAllBoolVars() {
    return _allBoolVars;
  }

  Set<ArithExpr> getAllArithVars() {
    return _allArithVars;
  }

  Set<BitVecExpr> getAllBVVars() {
    return _allBVVars;
  }

  Map<BitVecExpr, Integer> getAllBVValues() {
    return _allBVValues;
  }

  List<Symbol> getAllBoolVarsList() {
    return _allBoolVarsList;
  }

  List<Symbol> getAllArithVarsList() {
    return _allArithVarsList;
  }

  List<Symbol> getAllBVVarsList() {
    return _allBVVarsList;
  }

  Map<Symbol, Integer> getAllBVValuesMap() {
    return _allBVValuesMap;
  }


}
