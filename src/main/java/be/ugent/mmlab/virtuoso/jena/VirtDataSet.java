/*
 *  $Id$
 *
 *  This file is part of the OpenLink Software Virtuoso Open-Source (VOS)
 *  project.
 *
 *  Copyright (C) 1998-2013 OpenLink Software
 *
 *  This project is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU General Public License as published by the
 *  Free Software Foundation; only version 2 of the License, dated June 1991.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along
 *  with this program; if not, write to the Free Software Foundation, Inc.,
 *  51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA
 *
 */
package be.ugent.mmlab.virtuoso.jena;

import java.sql.*;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.atlas.iterator.Transform;


import com.hp.hpl.jena.shared.*;
import com.hp.hpl.jena.graph.Graph;
import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.query.Dataset;
import com.hp.hpl.jena.query.LabelExistsException;
import com.hp.hpl.jena.query.ReadWrite;
import com.hp.hpl.jena.rdf.model.Model;
import com.hp.hpl.jena.rdf.model.ModelFactory;
import com.hp.hpl.jena.shared.Lock;
import com.hp.hpl.jena.sparql.core.DatasetGraph;
import com.hp.hpl.jena.sparql.core.Quad;
import com.hp.hpl.jena.sparql.util.Context;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;

import virtuoso.jdbc3.VirtuosoDataSource;

//http://www.openlinksw.com/schemas/virtrdf#
//http://localhost:8890/DAV
//http://www.w3.org/2002/07/owl#


public class VirtDataSet extends VirtGraph implements Dataset {

    /**
     * Default model - may be null - according to Javadoc
     */
    Model defaultModel = null;
    private final Lock lock = new LockMRSW();
    private Context context = new Context();

    public VirtDataSet() {
        super();
    }

    public VirtDataSet(String _graphName, VirtuosoDataSource _ds) {
        super(_graphName, _ds);
    }

    public VirtDataSet(VirtGraph g) {

        this.graphName = g.getGraphName();
        setReadFromAllGraphs(g.getReadFromAllGraphs());
        this.url_hostlist = g.getGraphUrl();
        this.user = g.getGraphUser();
        this.password = g.getGraphPassword();
        this.roundrobin = g.isRoundrobin();
        setFetchSize(g.getFetchSize());
        this.connection = g.getConnection();
    }

    public VirtDataSet(String url_hostlist, String user, String password) {
        super(url_hostlist, user, password);
    }

    /**
     * Set the background graph. Can be set to null for none.
     */
    public void setDefaultModel(Model model) {
        if (!(model instanceof VirtDataSet)) {
            throw new IllegalArgumentException("VirtDataSource supports only VirtModel as default model");
        }
        defaultModel = model;
    }

    /**
     * Set a named graph.
     */
    public void addNamedModel(String name, Model model) throws LabelExistsException {
        String query = "select count(*) from (sparql select * where { graph `iri(??)` { ?s ?p ?o }})f";
        ResultSet rs = null;
        int ret = 0;

        checkOpen();
        try {
            java.sql.PreparedStatement ps = prepareStatement(query);
            ps.setString(1, name);
            rs = ps.executeQuery();
            if (rs.next()) {
                ret = rs.getInt(1);
            }
            rs.close();
        } catch (Exception e) {
            throw new JenaException(e);
        }

        try {
            if (ret != 0) {
                throw new LabelExistsException("A model with ID '" + name
                        + "' already exists.");
            }
            Graph g = model.getGraph();
            int count = 0;
            java.sql.PreparedStatement ps = prepareStatement(sinsert);

            for (Iterator i = g.find(Node.ANY, Node.ANY, Node.ANY); i.hasNext();) {
                Triple t = (Triple) i.next();

                ps.setString(1, name);
                bindSubject(ps, 2, t.getSubject());
                bindPredicate(ps, 3, t.getPredicate());
                bindObject(ps, 4, t.getObject());
                ps.addBatch();
                count++;
                if (count > BATCH_SIZE) {
                    ps.executeBatch();
                    ps.clearBatch();
                    count = 0;
                }
            }
            if (count > 0) {
                ps.executeBatch();
                ps.clearBatch();
            }
        } catch (Exception e) {
            throw new JenaException(e);
        }
    }

    /**
     * Remove a named graph.
     */
    public void removeNamedModel(String name) {
        String exec_text = "sparql clear graph <" + name + ">";

        checkOpen();
        try {
            java.sql.Statement stmt = createStatement();
            stmt.executeQuery(exec_text);
        } catch (Exception e) {
            throw new JenaException(e);
        }
    }

    /**
     * Change a named graph for another uisng the same name
     */
    public void replaceNamedModel(String name, Model model) {
        try {
            getConnection().setAutoCommit(false);
            removeNamedModel(name);
            addNamedModel(name, model);
            getConnection().commit();
            getConnection().setAutoCommit(true);
        } catch (Exception e) {
            try {
                getConnection().rollback();
            } catch (Exception e2) {
                throw new JenaException(
                        "Could not replace model, and could not rollback!", e2);
            }
            throw new JenaException("Could not replace model:", e);
        }
    }

    /**
     * Get the default graph as a Jena Model
     */
    public Model getDefaultModel() {
        return defaultModel;
    }

    /**
     * Get a graph by name as a Jena Model
     */
    public Model getNamedModel(String name) {
        try {
            VirtuosoDataSource _ds = getDataSource();
            if (_ds != null) {
                return new VirtModel(new VirtGraph(name, _ds));
            } else {
                return new VirtModel(new VirtGraph(name, this.getGraphUrl(),
                        this.getGraphUser(), this.getGraphPassword()));
            }
        } catch (Exception e) {
            throw new JenaException(e);
        }
    }

    /**
     * Does the dataset contain a model with the name supplied?
     */
    public boolean containsNamedModel(String name) {
        String query = "select count(*) from (sparql select * where { graph `iri(??)` { ?s ?p ?o }})f";
        ResultSet rs = null;
        int ret = 0;

        checkOpen();
        try {
            java.sql.PreparedStatement ps = prepareStatement(query);
            ps.setString(1, name);
            rs = ps.executeQuery();
            if (rs.next()) {
                ret = rs.getInt(1);
            }
            rs.close();
        } catch (Exception e) {
            throw new JenaException(e);
        }
        return (ret != 0);
    }

    /**
     * List the names
     */
    public Iterator<String> listNames() {
        String exec_text = "DB.DBA.SPARQL_SELECT_KNOWN_GRAPHS()";
        ResultSet rs = null;
        int ret = 0;

        checkOpen();
        try {
            List<String> names = new LinkedList();

            java.sql.Statement stmt = createStatement();
            rs = stmt.executeQuery(exec_text);
            while (rs.next()) {
                names.add(rs.getString(1));
            }
            return names.iterator();
        } catch (Exception e) {
            throw new JenaException(e);
        }
    }

    /**
     * Get the lock for this dataset
     */
    public Lock getLock() {

        return lock;
    }

    /**
     * Get the dataset in graph form
     */
    public DatasetGraph asDatasetGraph() {
        return new VirtDataSetGraph(this);
    }

    /* (non-Javadoc)
     * @see com.hp.hpl.jena.query.Dataset#getContext()
     */
    public Context getContext() {
        // TODO Auto-generated method stub
        return context;
    }

    /* (non-Javadoc)
     * @see com.hp.hpl.jena.query.Dataset#supportsTransactions()
     */
    public boolean supportsTransactions() {
        // TODO Auto-generated method stub
        return false;
    }

    /* (non-Javadoc)
     * @see com.hp.hpl.jena.query.Dataset#begin(com.hp.hpl.jena.query.ReadWrite)
     */
    public void begin(ReadWrite readWrite) {
        // TODO Auto-generated method stub
        this.getTransactionHandler().begin();

    }

    /* (non-Javadoc)
     * @see com.hp.hpl.jena.query.Dataset#commit()
     */
    public void commit() {
        // TODO Auto-generated method stub
        this.getTransactionHandler().commit();
    }

    /* (non-Javadoc)
     * @see com.hp.hpl.jena.query.Dataset#abort()
     */
    public void abort() {

        this.getTransactionHandler().abort();
    }

    /* (non-Javadoc)
     * @see com.hp.hpl.jena.query.Dataset#isInTransaction()
     */
    public boolean isInTransaction() {
        // TODO Auto-generated method stub
        return false;
    }


    /* (non-Javadoc)
     * @see com.hp.hpl.jena.query.Dataset#end()
     */
    public void end() {
        // TODO Auto-generated method stub
    }

    public class VirtDataSetGraph implements DatasetGraph {

        VirtDataSet vd = null;

        public VirtDataSetGraph(VirtDataSet vds) {
            vd = vds;
        }

        public Graph getDefaultGraph() {
            return vd;
        }

        public Graph getGraph(Node graphNode) {
            try {
                return new VirtGraph(graphNode.toString(), vd.getGraphUrl(),
                        vd.getGraphUser(), vd.getGraphPassword());
            } catch (Exception e) {
                throw new JenaException(e);
            }
        }

        public boolean containsGraph(Node graphNode) {
            return containsNamedModel(graphNode.toString());
        }

        public Iterator<Node> listGraphNodes() {
            String exec_text = "DB.DBA.SPARQL_SELECT_KNOWN_GRAPHS()";
            ResultSet rs = null;
            int ret = 0;

            vd.checkOpen();
            try {
                List<Node> names = new LinkedList();

                java.sql.Statement stmt = vd.createStatement();
                rs = stmt.executeQuery(exec_text);
                while (rs.next()) {
                    names.add(Node.createURI(rs.getString(1)));
                }
                return names.iterator();
            } catch (Exception e) {
                throw new JenaException(e);
            }
        }

        public Lock getLock() {
            return vd.getLock();
        }

        public long size() {
            return vd.size();
        }

        public void close() {
            vd.close();
        }

        public void setDefaultGraph(Graph g) {
            //SAM
            try {
                getConnection().setAutoCommit(false);
                setDefaultModel(ModelFactory.createModelForGraph(g));
                getConnection().commit();
                getConnection().setAutoCommit(true);
            } catch (Exception e) {
                try {
                    getConnection().rollback();
                } catch (Exception e2) {
                    throw new JenaException(
                            "Could not set the default model, and could not rollback!", e2);
                }
                throw new JenaException("Could not set the default model:", e);
            }
        }

        public void addGraph(Node graphName, Graph graph) {
            //SAM
            try {
                getConnection().setAutoCommit(false);
                addNamedModel(graphName.toString(), ModelFactory.createModelForGraph(graph));
                getConnection().commit();
                getConnection().setAutoCommit(true);
            } catch (Exception e) {
                try {
                    getConnection().rollback();
                } catch (Exception e2) {
                    throw new JenaException(
                            "Could not add the named model, and could not rollback!", e2);
                }
                throw new JenaException("Could not add the named model:", e);
            }
        }

        public void removeGraph(Node graphName) {
            //SAM
            try {
                getConnection().setAutoCommit(false);
                removeNamedModel(graphName.toString());
                getConnection().commit();
                getConnection().setAutoCommit(true);
            } catch (Exception e) {
                try {
                    getConnection().rollback();
                } catch (Exception e2) {
                    throw new JenaException(
                            "Could not remove the named model, and could not rollback!", e2);
                }
                throw new JenaException("Could not remove the named model:", e);
            }
        }

        public void add(Quad quad) {
            add(quad.getGraph(), quad.getSubject(), quad.getPredicate(), quad.getObject());
        }

        public void delete(Quad quad) {
            delete(quad.getGraph(), quad.getSubject(), quad.getPredicate(), quad.getObject());
        }

        public void deleteAny(Node g, Node s, Node p, Node o) {
            //SAM
            vd.checkOpen();
            ExtendedIterator<Triple> tripleIt = vd.find(s, p, o);
            List<Triple> list = Iter.toList(tripleIt);
            for (Triple q : list) {
                delete(g, q.getSubject(), q.getPredicate(), q.getObject());
            }


        }

        public Iterator<Quad> find() {
            //SAM
            vd.checkOpen();
            return triples2quads(null, vd.find(null, null, null));
        }

        public Iterator<Quad> find(Quad quad) {
            //SAM
            vd.checkOpen();
            return triples2quads(quad.getGraph(), vd.find(quad.getSubject(), quad.getPredicate(), quad.getObject()));
        }

        public Iterator<Quad> find(Node g, Node s, Node p, Node o) {
            //SAM
            vd.checkOpen();
            return triples2quads(g, vd.find(s, p, o));
        }

        public Iterator<Quad> findNG(Node g, Node s, Node p, Node o) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public boolean contains(Node g, Node s, Node p, Node o) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public boolean contains(Quad quad) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        public boolean isEmpty() {
            return vd.isEmpty();
        }

        public Context getContext() {
            return vd.getContext();
        }

        public void add(Node g, Node s, Node p, Node o) {
            String objectSurrounderLeft = "";
            String objectSurrounderRight = "";
            if (o.isURI()) {
                objectSurrounderLeft = "<";
                objectSurrounderRight = ">";
            }
            String exec_text = "sparql insert in graph <" + g.toString() + "> {<" + s.toString() + "> <" + p.toString() + "> " + objectSurrounderLeft + o.toString() + objectSurrounderRight + "}";

            checkOpen();
            try {
                java.sql.Statement stmt = createStatement();
                stmt.executeQuery(exec_text);
            } catch (Exception e) {
                throw new JenaException(e);
            }

        }

        public void delete(Node g, Node s, Node p, Node o) {
            String objectSurrounderLeft = "";
            String objectSurrounderRight = "";
            if (o.isURI()) {
                objectSurrounderLeft = "<";
                objectSurrounderRight = ">";
            }
            String exec_text = "sparql delete from graph <" + g.toString() + "> {<" + s.toString() + "> <" + p.toString() + "> " + objectSurrounderLeft + o.toString() + objectSurrounderRight + "}";

            checkOpen();
            try {
                java.sql.Statement stmt = createStatement();
                stmt.executeQuery(exec_text);
            } catch (Exception e) {
                throw new JenaException(e);
            }

        }

        protected Iter<Quad> triples2quads(final Node graphNode, Iterator<Triple> iter) {
            Transform<Triple, Quad> transformNamedGraph = new Transform<Triple, Quad>() {
                public Quad convert(Triple triple) {
                    return new Quad(graphNode, triple);
                }
            };

            return Iter.iter(iter).map(transformNamedGraph);
        }
    }
}