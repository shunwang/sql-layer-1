insert into customers (cid, name) select cid+100, name from customers INTERSECT select iid, oid from items