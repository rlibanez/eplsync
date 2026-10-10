package com.rlibanez.eplsync.ordering;

import java.text.Collator;
import java.text.Normalizer;
import java.util.*;
import java.sql.*;
import jakarta.persistence.criteria.*;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

/** Spanish alphabetical order, independent of machine locale. Only used for ORDER BY. */
public final class TextOrdering {
    public static final String COLLATION="EPL_TEXT";
    private static final ThreadLocal<Collator> COLLATOR=ThreadLocal.withInitial(()->{
        var value=Collator.getInstance(Locale.forLanguageTag("es"));
        value.setStrength(Collator.PRIMARY);value.setDecomposition(Collator.CANONICAL_DECOMPOSITION);return value;
    });
    private static final Set<String> TEXT_FIELDS=Set.of("title","author","genres","collection","client","lastError","message","eventActorUsername");
    private TextOrdering() {}
    public static String key(String text) {
        var value=Normalizer.normalize(text,Normalizer.Form.NFC);
        int start=0;
        while(start<value.length() && !Character.isLetterOrDigit(value.codePointAt(start)))
            start+=Character.charCount(value.codePointAt(start));
        return value.substring(start);
    }
    public static int compare(String left,String right) {
        String a=key(left),b=key(right);int i=0,j=0;
        while(i<a.length() && j<b.length()) {
            boolean numberA=asciiDigit(a.charAt(i)),numberB=asciiDigit(b.charAt(j));
            int endA=i+1,endB=j+1;
            while(endA<a.length() && asciiDigit(a.charAt(endA))==numberA) endA++;
            while(endB<b.length() && asciiDigit(b.charAt(endB))==numberB) endB++;
            int result;
            if(numberA && numberB) {
                int zeroA=i,zeroB=j;
                while(zeroA<endA-1 && a.charAt(zeroA)=='0') zeroA++;
                while(zeroB<endB-1 && b.charAt(zeroB)=='0') zeroB++;
                result=Integer.compare(endA-zeroA,endB-zeroB);
                if(result==0) result=a.substring(zeroA,endA).compareTo(b.substring(zeroB,endB));
            } else result=COLLATOR.get().compare(a.substring(i,endA),b.substring(j,endB));
            if(result!=0) return result;
            i=endA;j=endB;
        }
        return Integer.compare(a.length()-i,b.length()-j);
    }
    private static boolean asciiDigit(char value) {return value>='0' && value<='9';}
    public static void register(Connection connection) throws SQLException {
        org.sqlite.Collation.create(connection,COLLATION,new org.sqlite.Collation() {
            @Override protected int xCompare(String a,String b) {return compare(a,b);}
        });
    }
    public static String sql(String expression) {return "("+expression+") COLLATE "+COLLATION;}
    @SuppressWarnings("unchecked")
    public static Order order(CriteriaBuilder cb,Root<?> root,Sort.Order order) {
        String[] parts=order.getProperty().split("\\.");Path<?> path=root;
        if(parts.length>1) {
            var join=root.getJoins().stream().filter(value->value.getAttribute().getName().equals(parts[0]) && value.getJoinType()==JoinType.LEFT).findFirst();
            path=join.isPresent()?join.get():root.join(parts[0],JoinType.LEFT);
            for(int i=1;i<parts.length;i++) path=path.get(parts[i]);
        } else path=root.get(parts[0]);
        Expression<?> expression=path;
        if(path.getJavaType()==String.class && TEXT_FIELDS.contains(parts[parts.length-1]))
            expression=((HibernateCriteriaBuilder)cb).collate((Expression<String>)path,COLLATION);
        return order.isAscending()?cb.asc(expression):cb.desc(expression);
    }
    public static <T> Specification<T> sorted(Specification<T> filter,Sort sort) {
        return (root,query,cb)->{
            var predicate=filter.toPredicate(root,query,cb);
            if(query!=null && query.getResultType()!=Long.class && query.getResultType()!=long.class)
                query.orderBy(sort.stream().map(value->order(cb,root,value)).toList());
            return predicate;
        };
    }
}
